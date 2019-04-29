/* 
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2015 The OmniROM Project
 */
/* 
 * This file is part of OpenDelta.
 * 
 * OpenDelta is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * OpenDelta is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with OpenDelta. If not, see <http://www.gnu.org/licenses/>.
 */

package eu.chainfire.opendelta

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.lang.reflect.Method
import java.math.BigInteger
import java.net.UnknownHostException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList
import java.util.Arrays
import java.util.Locale
import java.util.Date
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UpdateEngine
import android.preference.PreferenceManager
import android.text.TextUtils

import eu.chainfire.opendelta.BatteryState.OnBatteryStateListener
import eu.chainfire.opendelta.DeltaInfo.ProgressListener
import eu.chainfire.opendelta.NetworkState.OnNetworkStateListener
import eu.chainfire.opendelta.Scheduler.OnWantUpdateCheckListener
import eu.chainfire.opendelta.ScreenState.OnScreenStateListener

class UpdateService : Service(), OnNetworkStateListener, OnBatteryStateListener, OnScreenStateListener, OnWantUpdateCheckListener, OnSharedPreferenceChangeListener {

    var config: Config? = null
        private set

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var state = STATE_ACTION_NONE

    private var networkState: NetworkState? = null
    private var batteryState: BatteryState? = null
    private var screenState: ScreenState? = null

    private var scheduler: Scheduler? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var notificationManager: NotificationManager? = null
    private var stopDownload: Boolean = false
    private var updateRunning: Boolean = false
    private var failedUpdateCount: Int = 0
    var prefs: SharedPreferences? = null
        private set
    private var mBuilder: Notification.Builder? = null
    private var isProgressNotificationDismissed = false
    private var progressUpdateStart: Long = 0

    private val progressNotificationIntent: PendingIntent
        get() {
            val notificationIntent = Intent(this, UpdateService::class.java)
            notificationIntent.action = ACTION_PROGRESS_NOTIFICATION_DISMISSED
            return PendingIntent.getService(this, 0, notificationIntent, 0)
        }

    private// latest build can have a larger micro version then what we run now
    val newestFullBuild: String?
        get() {
            Logger.d("Checking for latest full build")

            val url = config!!.urlBaseJson

            val buildData = downloadUrlMemoryAsString(url)
            if (buildData == null || buildData.length == 0) {
                updateState(STATE_ERROR_DOWNLOAD, null, null, null, url, null)
                return null
            }
            var `object`: JSONObject? = null
            try {
                `object` = JSONObject(buildData)
                val nextKey = `object`.keys()
                var latestBuild: String? = null
                var latestTimestamp: Long = 0
                while (nextKey.hasNext()) {
                    val key = nextKey.next()
                    if (key == "./" + config!!.device!!) {
                        val builds = `object`.getJSONArray(key)
                        for (i in 0 until builds.length()) {
                            val build = builds.getJSONObject(i)
                            val fileName = File(build.getString("filename")).name
                            val timestamp = build.getLong("timestamp")
                            if (isMatchingImage(fileName) && timestamp > latestTimestamp) {
                                latestBuild = fileName
                                latestTimestamp = timestamp
                            }
                        }
                    }
                }
                if (latestBuild != null) {
                    return latestBuild
                }
            } catch (e: Exception) {
                Logger.ex(e)
            }

            updateState(STATE_ERROR_UNOFFICIAL, null, null, null, config!!.version, null)
            return null
        }

    private val isSupportedVersion: Boolean
        get() = config!!.isOfficialVersion

    private val autoDownloadValue: Int
        get() {
            val autoDownload = prefs!!.getString(SettingsActivity.PREF_AUTO_DOWNLOAD, defaultAutoDownloadValue)
            return Integer.valueOf(autoDownload).toInt()
        }

    private val defaultAutoDownloadValue: String
        get() = if (isSupportedVersion) PREF_AUTO_DOWNLOAD_CHECK_STRING else PREF_AUTO_DOWNLOAD_DISABLED_STRING

    private// only when screen off
    // always allow
    val isScreenStateEnabled: Boolean
        get() {
            if (screenState == null) {
                return false
            }
            val screenStateValue = screenState!!.state!!
            val prefValue = prefs!!.getBoolean(SettingsActivity.PREF_SCREEN_STATE_OFF, true)
            return if (prefValue) {
                !screenStateValue
            } else true
        }

    private// check if we're snoozed, using abs for clock changes
    // only snooze if time snoozed and no newer update available
    val isSnoozeNotification: Boolean
        get() {
            val timeSnooze = Math.abs(System.currentTimeMillis() - prefs!!.getLong(PREF_LAST_SNOOZE_TIME_NAME,
                    PREF_LAST_SNOOZE_TIME_DEFAULT)) <= SNOOZE_MS
            if (timeSnooze) {
                val lastBuild = prefs!!.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT)
                val snoozeBuild = prefs!!.getString(PREF_SNOOZE_UPDATE_NAME, PREF_READY_FILENAME_DEFAULT)
                if (lastBuild !== PREF_READY_FILENAME_DEFAULT && snoozeBuild !== PREF_READY_FILENAME_DEFAULT) {
                    if (lastBuild != snoozeBuild) {
                        return false
                    }
                }
            }
            return timeSnooze
        }


    /*
     * Using reflection voodoo instead calling the hidden class directly, to
     * dev/test outside of AOSP tree
     */
    private fun setPermissions(path: String, mode: Int, uid: Int, gid: Int): Boolean {
        try {
            val FileUtils = classLoader.loadClass(
                    "android.os.FileUtils")
            val setPermissions = FileUtils.getDeclaredMethod(
                    "setPermissions", *arrayOf(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            return setPermissions.invoke(null,
                    *arrayOf(path, Integer.valueOf(mode), Integer.valueOf(uid), Integer.valueOf(gid))) as Int == 0
        } catch (e: Exception) {
            // A lot of voodoo could go wrong here, return failure instead of
            // crash
            Logger.ex(e)
        }

        return false
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        config = Config.getInstance(this)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(
                        if (config!!.keepScreenOn)
                            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        else
                            PowerManager.PARTIAL_WAKE_LOCK,
                        "OpenDelta WakeLock")
        wifiLock = (getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL,
                        "OpenDelta WifiLock")

        handlerThread = HandlerThread("OpenDelta Service Thread")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        scheduler = Scheduler(this, this)
        val autoDownload = autoDownloadValue
        if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            scheduler!!.start()
        }
        networkState = NetworkState()
        networkState!!.start(this, this, prefs!!.getInt(
                PREF_AUTO_UPDATE_NETWORKS_NAME,
                PREF_AUTO_UPDATE_NETWORKS_DEFAULT))

        batteryState = BatteryState()
        batteryState!!.start(this, this,
                Integer.valueOf(prefs!!.getString(SettingsActivity.PREF_BATTERY_LEVEL, "50")).toInt(),
                prefs!!.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true))

        screenState = ScreenState()
        screenState!!.start(this, this)

        prefs!!.registerOnSharedPreferenceChangeListener(this)

        autoState(false, PREF_AUTO_DOWNLOAD_CHECK, false)
    }

    override fun onDestroy() {
        prefs!!.unregisterOnSharedPreferenceChangeListener(this)
        networkState!!.stop()
        batteryState!!.stop()
        screenState!!.stop()
        handlerThread!!.quitSafely()

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (ACTION_CHECK == intent.action) {
                if (checkPermissions()) {
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_CHECK)
                }
            } else if (ACTION_FLASH == intent.action) {
                if (checkPermissions()) {
                    if (Config.isABDevice) {
                        flashABUpdate()
                    } else {
                        flashUpdate()
                    }
                }
            } else if (ACTION_ALARM == intent.action) {
                scheduler!!.alarm(intent.getIntExtra(EXTRA_ALARM_ID, -1))
            } else if (ACTION_NOTIFICATION_DELETED == intent.action) {
                prefs!!.edit().putLong(PREF_LAST_SNOOZE_TIME_NAME,
                        System.currentTimeMillis()).commit()
                val lastBuild = prefs!!.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT)
                if (lastBuild !== PREF_READY_FILENAME_DEFAULT) {
                    // only snooze until no newer build is available
                    Logger.i("Snoozing notification for " + lastBuild!!)
                    prefs!!.edit().putString(PREF_SNOOZE_UPDATE_NAME, lastBuild).commit()
                }
            } else if (ACTION_PROGRESS_NOTIFICATION_DISMISSED == intent.action) {
                isProgressNotificationDismissed = true
            } else if (ACTION_BUILD == intent.action) {
                if (checkPermissions()) {
                    checkForUpdates(true, PREF_AUTO_DOWNLOAD_FULL)
                }
            } else if (ACTION_UPDATE == intent.action) {
                autoState(true, PREF_AUTO_DOWNLOAD_CHECK, false)
            } else if (ACTION_CLEAR_INSTALL_RUNNING == intent.action) {
                ABUpdate.setInstallingUpdate(false, this)
            }
        }

        return Service.START_STICKY
    }

    @Synchronized
    private fun updateState(state: String, progress: Float?,
                            current: Long?, total: Long?, filename: String?, ms: Long?) {
        this.state = state

        val i = Intent(BROADCAST_INTENT)
        i.putExtra(EXTRA_STATE, state)
        if (progress != null)
            i.putExtra(EXTRA_PROGRESS, progress)
        if (current != null)
            i.putExtra(EXTRA_CURRENT, current)
        if (total != null)
            i.putExtra(EXTRA_TOTAL, total)
        if (filename != null)
            i.putExtra(EXTRA_FILENAME, filename)
        if (ms != null)
            i.putExtra(EXTRA_MS, ms)

        sendStickyBroadcast(i)
    }

    override fun onNetworkState(state: Boolean) {
        Logger.d("network state --> %d", if (state) 1 else 0)
    }

    override fun onBatteryState(state: Boolean) {
        Logger.d("battery state --> %d", if (state) 1 else 0)
    }

    override fun onScreenState(state: Boolean) {
        Logger.d("screen state --> %d", if (state) 1 else 0)
        scheduler!!.onScreenState(state)
    }

    override fun onWantUpdateCheck(): Boolean {
        if (isProgressState(state)) {
            Logger.i("Blocked scheduler requests while running in state $state")
            return false
        }
        Logger.i("Scheduler requests check for updates")
        val autoDownload = autoDownloadValue
        return if (autoDownload != PREF_AUTO_DOWNLOAD_DISABLED) {
            checkForUpdates(false, autoDownload)
        } else false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences,
                                           key: String) {
        Logger.d("onSharedPreferenceChanged $key")

        if (PREF_AUTO_UPDATE_NETWORKS_NAME == key) {
            networkState!!.updateFlags(sharedPreferences.getInt(
                    PREF_AUTO_UPDATE_NETWORKS_NAME,
                    PREF_AUTO_UPDATE_NETWORKS_DEFAULT))
        }
        if (PREF_STOP_DOWNLOAD == key) {
            stopDownload = true
        }
        if (SettingsActivity.PREF_AUTO_DOWNLOAD == key) {
            val autoDownload = autoDownloadValue
            if (autoDownload == PREF_AUTO_DOWNLOAD_DISABLED) {
                scheduler!!.stop()
            } else {
                scheduler!!.start()
            }
        }
        if (batteryState != null) {
            batteryState!!.onSharedPreferenceChanged(sharedPreferences, key)
        }
        if (scheduler != null) {
            scheduler!!.onSharedPreferenceChanged(sharedPreferences, key)
        }
    }

    private fun autoState(userInitiated: Boolean, checkOnly: Int, notify: Boolean) {
        var checkOnly = checkOnly
        Logger.d("autoState state = " + this.state + " userInitiated = " + userInitiated + " checkOnly = " + checkOnly)

        if (isErrorState(this.state)) {
            return
        }
        if (stopDownload) {
            // stop download is only possible in the download step
            // that means must have done a check step before
            // so just fall back to this instead to show none state
            // which is just confusing
            checkOnly = PREF_AUTO_DOWNLOAD_CHECK
        }
        var filename = prefs!!.getString(PREF_READY_FILENAME_NAME,
                PREF_READY_FILENAME_DEFAULT)

        if (filename !== PREF_READY_FILENAME_DEFAULT) {
            if (!File(filename!!).exists()) {
                filename = null
            }
        }

        val updateAvilable = updateAvailable()
        // if the file has been downloaded or creates anytime before
        // this will aways be more important
        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK && filename == null) {
            Logger.d("Checking step done")
            if (!updateAvilable) {
                Logger.d("System up to date")
                updateState(STATE_ACTION_NONE, null, null, null, null,
                        prefs!!.getLong(PREF_LAST_CHECK_TIME_NAME,
                                PREF_LAST_CHECK_TIME_DEFAULT))
            } else {
                Logger.d("Update available")
                updateState(STATE_ACTION_BUILD, null, null, null, null,
                        prefs!!.getLong(PREF_LAST_CHECK_TIME_NAME,
                                PREF_LAST_CHECK_TIME_DEFAULT))
                if (!userInitiated && notify) {
                    if (!isSnoozeNotification) {
                        startNotification(checkOnly)
                    } else {
                        Logger.d("notification snoozed")
                    }
                }
            }
            return
        }

        if (filename == null) {
            Logger.d("System up to date")
            updateState(STATE_ACTION_NONE, null, null, null, null,
                    prefs!!.getLong(PREF_LAST_CHECK_TIME_NAME,
                            PREF_LAST_CHECK_TIME_DEFAULT))
        } else {
            Logger.d("Update found: %s", filename)
            updateState(STATE_ACTION_READY, null, null, null, File(
                    filename).name, prefs!!.getLong(
                    PREF_LAST_CHECK_TIME_NAME, PREF_LAST_CHECK_TIME_DEFAULT))

            if (!userInitiated && notify) {
                if (!isSnoozeNotification) {
                    startNotification(checkOnly)
                } else {
                    Logger.d("notification snoozed")
                }
            }
        }
    }

    private fun getNotificationIntent(delete: Boolean): PendingIntent {
        if (delete) {
            val notificationIntent = Intent(this, UpdateService::class.java)
            notificationIntent.action = ACTION_NOTIFICATION_DELETED
            return PendingIntent.getService(this, 0, notificationIntent, 0)
        } else {
            val notificationIntent = Intent(this, MainActivity::class.java)
            notificationIntent.action = ACTION_SYSTEM_UPDATE_SETTINGS
            return PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }
    }

    private fun startNotification(checkOnly: Int) {
        val latestFull = prefs!!.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT)
        if (latestFull === PREF_READY_FILENAME_DEFAULT) {
            return
        }
        var flashFilename = prefs!!.getString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT)
        val readyToFlash = flashFilename !== PREF_READY_FILENAME_DEFAULT
        if (readyToFlash) {
            flashFilename = File(flashFilename!!).name
            flashFilename!!.substring(0, flashFilename.lastIndexOf('.'))
        }

        val notifyFileName = if (readyToFlash) flashFilename else latestFull!!.substring(0, latestFull.lastIndexOf('.'))

        notificationManager!!.notify(
                NOTIFICATION_UPDATE,
                Notification.Builder(this)
                        .setSmallIcon(R.drawable.stat_notify_update)
                        .setContentTitle(if (readyToFlash) getString(R.string.notify_title_flash) else getString(R.string.notify_title_download))
                        .setShowWhen(true)
                        .setContentIntent(getNotificationIntent(false))
                        .setDeleteIntent(getNotificationIntent(true))
                        .setContentText(notifyFileName).build())
    }

    private fun startABRebootNotification() {
        var flashFilename = prefs!!.getString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT)
        flashFilename = File(flashFilename!!).name
        flashFilename!!.substring(0, flashFilename.lastIndexOf('.'))

        notificationManager!!.notify(
                NOTIFICATION_UPDATE,
                Notification.Builder(this)
                        .setSmallIcon(R.drawable.stat_notify_update)
                        .setContentTitle(getString(R.string.state_action_ab_finished))
                        .setShowWhen(true)
                        .setContentIntent(getNotificationIntent(false))
                        .setDeleteIntent(getNotificationIntent(true))
                        .setContentText(flashFilename).build())
    }

    private fun stopNotification() {
        notificationManager!!.cancel(NOTIFICATION_UPDATE)
    }

    private fun startErrorNotification() {
        var errorStateString: String? = null
        try {
            errorStateString = getString(resources.getIdentifier(
                    "state_$state", "string", packageName))
        } catch (e: Exception) {
            // String for this state could not be found (displays empty string)
            Logger.ex(e)
        }

        if (errorStateString != null) {
            notificationManager!!.notify(
                    NOTIFICATION_ERROR,
                    Notification.Builder(this)
                            .setSmallIcon(R.drawable.stat_notify_error)
                            .setContentTitle(getString(R.string.notify_title_error))
                            .setContentText(errorStateString)
                            .setShowWhen(true)
                            .setContentIntent(getNotificationIntent(false)).build())
        }
    }

    private fun stopErrorNotification() {
        notificationManager!!.cancel(NOTIFICATION_ERROR)
    }

    private fun setupHttpsRequest(urlStr: String): HttpsURLConnection? {
        val url: URL
        var urlConnection: HttpsURLConnection? = null
        try {
            url = URL(urlStr)
            urlConnection = url.openConnection() as HttpsURLConnection
            urlConnection.connectTimeout = HTTP_CONNECTION_TIMEOUT
            urlConnection.readTimeout = HTTP_READ_TIMEOUT
            urlConnection.requestMethod = "GET"
            urlConnection.doInput = true
            urlConnection.connect()
            val code = urlConnection.responseCode
            if (code != HttpsURLConnection.HTTP_OK) {
                Logger.d("response: %d", code)
                return null
            }
            return urlConnection
        } catch (e: Exception) {
            Logger.i("Failed to connect to server")
            return null
        }

    }

    private fun downloadUrlMemory(url: String): ByteArray? {
        Logger.d("download: %s", url)

        var urlConnection: HttpsURLConnection? = null
        try {
            urlConnection = setupHttpsRequest(url)
            if (urlConnection == null) {
                return null
            }

            val len = urlConnection.contentLength
            if (len >= 0 && len < 1024 * 1024) {
                val `is` = urlConnection.inputStream
                var byteInt: Int
                val byteArray = ByteArrayOutputStream()

                while ((byteInt = `is`.read()) >= 0) {
                    byteArray.write(byteInt)
                }

                return byteArray.toByteArray()
            }
            return null
        } catch (e: Exception) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e)
            return null
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect()
            }
        }
    }

    private fun downloadUrlMemoryAsString(url: String): String? {
        Logger.d("download: %s", url)

        var urlConnection: HttpsURLConnection? = null
        try {
            urlConnection = setupHttpsRequest(url)
            if (urlConnection == null) {
                return null
            }

            val `is` = urlConnection.inputStream
            val byteArray = ByteArrayOutputStream()
            var byteInt: Int

            while ((byteInt = `is`.read()) >= 0) {
                byteArray.write(byteInt)
            }

            val bytes = byteArray.toByteArray() ?: return null

            return String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e)
            return null
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect()
            }
        }
    }

    private fun downloadUrlFile(url: String, f: File, matchMD5: String?,
                                progressListener: DeltaInfo.ProgressListener?): Boolean {
        Logger.d("download: %s", url)

        var urlConnection: HttpsURLConnection? = null
        var digest: MessageDigest? = null
        if (matchMD5 != null) {
            try {
                digest = MessageDigest.getInstance("MD5")
            } catch (e: NoSuchAlgorithmException) {
                // No MD5 algorithm support
                Logger.ex(e)
            }

        }

        if (f.exists())
            f.delete()

        try {
            urlConnection = setupHttpsRequest(url)
            if (urlConnection == null) {
                return false
            }
            val len = urlConnection.contentLength.toLong()
            var recv: Long = 0
            if (len > 0 && len < 4L * 1024L * 1024L * 1024L) {
                val buffer = ByteArray(262144)

                val `is` = urlConnection.inputStream
                val os = FileOutputStream(f, false)
                try {
                    var r: Int
                    while ((r = `is`.read(buffer)) > 0) {
                        if (stopDownload) {
                            return false
                        }
                        os.write(buffer, 0, r)
                        if (digest != null)
                            digest.update(buffer, 0, r)

                        recv += r.toLong()
                        progressListener?.onProgress(
                                recv.toFloat() / len.toFloat() * 100f, recv,
                                len)
                    }
                } finally {
                    os.close()
                }

                if (digest != null) {
                    var MD5 = BigInteger(1, digest.digest())
                            .toString(16).toLowerCase(Locale.ENGLISH)
                    while (MD5.length < 32)
                        MD5 = "0$MD5"
                    val md5Check = MD5 == matchMD5
                    if (!md5Check) {
                        Logger.i("MD5 check failed for $url")
                    }
                    return md5Check
                }
                return true
            }
            return false
        } catch (e: Exception) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e)
            return false
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect()
            }
        }
    }

    private fun downloadUrlFileUnknownSize(url: String, f: File,
                                           matchMD5: String?): Boolean {
        Logger.d("download: %s", url)

        var urlConnection: HttpsURLConnection? = null
        var digest: MessageDigest? = null
        var len: Long = 0
        if (matchMD5 != null) {
            try {
                digest = MessageDigest.getInstance("MD5")
            } catch (e: NoSuchAlgorithmException) {
                // No MD5 algorithm support
                Logger.ex(e)
            }

        }

        if (f.exists())
            f.delete()

        try {
            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, 0L, f.name, null)
            urlConnection = setupHttpsRequest(url)
            if (urlConnection == null) {
                return false
            }

            len = urlConnection.contentLength.toLong()

            updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, len, f.name, null)

            val freeSpace = StatFs(config!!.pathBase)
                    .availableBytes
            if (freeSpace < len) {
                updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, len, null, null)
                Logger.d("not enough space!")
                return false
            }

            val last = longArrayOf(0, len, 0, SystemClock.elapsedRealtime())
            val progressListener = object : DeltaInfo.ProgressListener {
                override fun onProgress(progress: Float, current: Long, total: Long) {
                    var progress = progress
                    var current = current
                    var total = total
                    current += last[0]
                    total = last[1]
                    progress = current.toFloat() / total.toFloat() * 100f
                    val now = SystemClock.elapsedRealtime()
                    if (now >= last[2] + 16L) {
                        updateState(STATE_ACTION_DOWNLOADING, progress,
                                current, total, f.name,
                                SystemClock.elapsedRealtime() - last[3])
                        last[2] = now
                    }
                }

                override fun setStatus(s: String) {
                    // do nothing
                }
            }

            var recv: Long = 0
            if (len > 0 && len < 4L * 1024L * 1024L * 1024L) {
                val buffer = ByteArray(262144)

                val `is` = urlConnection.inputStream
                val os = FileOutputStream(f, false)
                try {
                    var r: Int
                    while ((r = `is`.read(buffer)) > 0) {
                        if (stopDownload) {
                            return false
                        }
                        os.write(buffer, 0, r)
                        if (digest != null)
                            digest.update(buffer, 0, r)

                        recv += r.toLong()
                        progressListener?.onProgress(
                                recv.toFloat() / len.toFloat() * 100f, recv,
                                len)
                    }
                } finally {
                    os.close()
                }

                if (digest != null) {
                    var MD5 = BigInteger(1, digest.digest())
                            .toString(16).toLowerCase(Locale.ENGLISH)
                    while (MD5.length < 32)
                        MD5 = "0$MD5"
                    val md5Check = MD5 == matchMD5
                    if (!md5Check) {
                        Logger.i("MD5 check failed for $url")
                    }
                    return md5Check
                }
                return true
            }
            return false
        } catch (e: Exception) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e)
            return false
        } finally {
            updateState(STATE_ACTION_DOWNLOADING, 100f, len, len, null, null)
            if (urlConnection != null) {
                urlConnection.disconnect()
            }
        }
    }

    private fun getUrlDownloadSize(url: String): Long {
        Logger.d("getUrlDownloadSize: %s", url)

        var urlConnection: HttpsURLConnection? = null
        try {
            urlConnection = setupHttpsRequest(url)
            return if (urlConnection == null) {
                0
            } else urlConnection.contentLength.toLong()

        } catch (e: Exception) {
            // Download failed for any number of reasons, timeouts, connection
            // drops, etc. Just log it in debugging mode.
            Logger.ex(e)
            return 0
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect()
            }
        }
    }

    private fun isMatchingImage(fileName: String): Boolean {
        try {
            if (fileName.endsWith(".zip") && fileName.indexOf(config!!.device!!) != -1) {
                val parts = fileName.split("-".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                if (parts.size > 1) {
                    val version = parts[1]
                    val current = Version(config!!.androidVersion)
                    val fileVersion = Version(version)
                    if (fileVersion.compareTo(current) >= 0) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Logger.ex(e)
        }

        return false
    }

    private fun getMD5Progress(state: String,
                               filename: String): DeltaInfo.ProgressListener {
        val last = longArrayOf(0, SystemClock.elapsedRealtime())
        val _state = state

        return object : DeltaInfo.ProgressListener {
            override fun onProgress(progress: Float, current: Long, total: Long) {
                val now = SystemClock.elapsedRealtime()
                if (now >= last[0] + 16L) {
                    updateState(_state, progress, current, total, filename,
                            SystemClock.elapsedRealtime() - last[1])
                    last[0] = now
                }
            }

            override fun setStatus(s: String) {
                // do nothing
            }
        }
    }

    private fun sizeOnDisk(size: Long): Long {
        // Assuming 256k block size here, should be future proof for a little
        // bit
        val blocks = (size + 262143L) / 262144L
        return blocks * 262144L
    }

    private fun downloadDeltaFile(url_base: String,
                                  fileBase: DeltaInfo.FileBase, match: DeltaInfo.FileSizeMD5,
                                  progressListener: DeltaInfo.ProgressListener, force: Boolean): Boolean {
        if (fileBase.tag == null) {
            if (force || networkState!!.state!!) {
                val url = url_base + fileBase.name
                val fn = config!!.pathBase + fileBase.name
                val f = File(fn)
                Logger.d("download: %s --> %s", url, fn)

                if (downloadUrlFile(url, f, match.mD5, progressListener)) {
                    fileBase.tag = fn
                    Logger.d("success")
                    return true
                } else {
                    f.delete()
                    if (stopDownload) {
                        Logger.d("download stopped")
                    } else {
                        updateState(STATE_ERROR_DOWNLOAD, null, null, null,
                                fn, null)
                        Logger.d("download error")
                    }
                    return false
                }
            } else {
                Logger.d("aborting download due to network state")
                return false
            }
        } else {
            Logger.d("have %s already", fileBase.name)
            return true
        }
    }

    private fun getThreadedProgress(filename: String, display: String,
                                    start: Long, currentOut: Long, totalOut: Long): Thread {
        val _file = File(filename)

        return Thread(Runnable {
            while (true) {
                try {
                    val current = currentOut + _file.length()
                    updateState(STATE_ACTION_APPLYING_PATCH,
                            current.toFloat() / totalOut.toFloat() * 100f,
                            current, totalOut, display,
                            SystemClock.elapsedRealtime() - start)

                    Thread.sleep(16)
                } catch (e: InterruptedException) {
                    // We're being told to quit
                    break
                }

            }
        })
    }

    private fun zipadjust(filenameIn: String?, filenameOut: String,
                          start: Long, currentOut: Long, totalOut: Long): Boolean {
        Logger.d("zipadjust [%s] --> [%s]", filenameIn, filenameOut)

        // checking filesizes in the background as progress, because these
        // native functions don't have callbacks (yet) to do this

        File(filenameOut).delete()

        val progress = getThreadedProgress(filenameOut,
                File(filenameIn!!).name, start, currentOut, totalOut)
        progress.start()

        val ok = Native.zipadjust(filenameIn, filenameOut, 1)

        progress.interrupt()
        try {
            progress.join()
        } catch (e: InterruptedException) {
            // We got interrupted in a very short wait, surprising, but not a
            // problem. 'progress' will quit by itself.
            Logger.ex(e)
        }

        Logger.d("zipadjust --> %d", ok)

        return ok == 1
    }

    private fun dedelta(filenameSource: String?, filenameDelta: String,
                        filenameOut: String, start: Long, currentOut: Long, totalOut: Long): Boolean {
        Logger.d("dedelta [%s] --> [%s] --> [%s]", filenameSource,
                filenameDelta, filenameOut)

        // checking filesizes in the background as progress, because these
        // native functions don't have callbacks (yet) to do this

        File(filenameOut).delete()

        val progress = getThreadedProgress(filenameOut, File(
                filenameDelta).name, start, currentOut, totalOut)
        progress.start()

        val ok = Native.dedelta(filenameSource, filenameDelta, filenameOut)

        progress.interrupt()
        try {
            progress.join()
        } catch (e: InterruptedException) {
            // We got interrupted in a very short wait, surprising, but not a
            // problem. 'progress' will quit by itself.
            Logger.ex(e)
        }

        Logger.d("dedelta --> %d", ok)

        return ok == 1
    }

    private fun checkForUpdates(userInitiated: Boolean, checkOnly: Int): Boolean {
        var checkOnly = checkOnly
        /*
         * Unless the user is specifically asking to check for updates, we only
         * check for them if we have a connection matching the user's set
         * preferences, we're charging and/or have juice aplenty (>50), and the screen
         * is off
         *
         * if user has enabled checking only we only check the screen state
         * cause the amount of data transferred for checking is not very large
         */

        if (networkState == null || batteryState == null
                || screenState == null)
            return false

        Logger.d("checkForUpdates checkOnly = " + checkOnly + " updateRunning = " + updateRunning + " userInitiated = " + userInitiated +
                " networkState.getState() = " + networkState!!.state + " batteryState.getState() = " + batteryState!!.state +
                " screenState.getState() = " + screenState!!.state)

        if (updateRunning) {
            Logger.i("Ignoring request to check for updates - busy")
            return false
        }

        stopNotification()
        stopErrorNotification()

        if (!isSupportedVersion) {
            // TODO - to be more generic this should maybe use the info from getNewestFullBuild
            updateState(STATE_ERROR_UNOFFICIAL, null, null, null, config!!.version, null)
            Logger.i("Ignoring request to check for updates - not compatible for update! " + config!!.version!!)
            return false
        }
        if (!networkState!!.isConnected) {
            updateState(STATE_ERROR_CONNECTION, null, null, null, null, null)
            Logger.i("Ignoring request to check for updates - no data connection")
            return false
        }
        var updateAllowed = false
        if (!userInitiated) {
            updateAllowed = checkOnly >= PREF_AUTO_DOWNLOAD_CHECK
            if (checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                // must confirm to all if we may auto download
                updateAllowed = (networkState!!.state!!
                        && batteryState!!.state!! && isScreenStateEnabled)
                if (!updateAllowed) {
                    // fallback to check only
                    checkOnly = PREF_AUTO_DOWNLOAD_CHECK
                    updateAllowed = true
                    Logger.i("Auto-dwonload not possible - fallback to check only")
                }
            }
        }

        if (userInitiated || updateAllowed) {
            Logger.i("Starting check for updates")
            checkForUpdatesAsync(userInitiated, checkOnly)
            return true
        } else {
            Logger.i("Ignoring request to check for updates")
        }
        return false
    }

    private fun getDeltaDownloadSize(deltas: List<DeltaInfo>): Long {
        updateState(STATE_ACTION_CHECKING, null, null, null, null, null)

        var deltaDownloadSize = 0L
        for (di in deltas) {
            val fn = config!!.pathBase + di.update.name
            if (di.update.match(
                            File(fn),
                            true,
                            getMD5Progress(STATE_ACTION_CHECKING_MD5, di.update
                                    .name)) === di.update.update) {
                di.update.tag = fn
            } else {
                deltaDownloadSize += di.update.update.size
            }
        }

        val lastDelta = deltas[deltas.size - 1]
        run {
            if (config!!.applySignature) {
                val fn = config!!.pathBase + lastDelta.signature.name
                if (lastDelta.signature.match(
                                File(fn),
                                true,
                                getMD5Progress(STATE_ACTION_CHECKING_MD5, lastDelta
                                        .signature.name)) === lastDelta
                                .signature.update) {
                    lastDelta.signature.tag = fn
                } else {
                    deltaDownloadSize += lastDelta.signature.update
                            .size
                }
            }
        }

        updateState(STATE_ACTION_CHECKING, null, null, null, null, null)

        return deltaDownloadSize
    }

    private fun getFullDownloadSize(deltas: List<DeltaInfo>): Long {
        val lastDelta = deltas[deltas.size - 1]
        return lastDelta.out.official.size
    }

    private fun getRequiredSpace(deltas: List<DeltaInfo>, getFull: Boolean): Long {
        val lastDelta = deltas[deltas.size - 1]

        var requiredSpace: Long = 0
        if (getFull) {
            requiredSpace += sizeOnDisk(if (lastDelta.out.tag != null)
                0
            else
                lastDelta.out.official.size)
        } else {
            // The resulting number will be a tad more than worst case what we
            // actually need, but not dramatically so

            for (di in deltas) {
                if (di.update.tag == null)
                    requiredSpace += sizeOnDisk(di.update.update
                            .size)
            }
            if (config!!.applySignature) {
                requiredSpace += sizeOnDisk(lastDelta.signature
                        .update.size)
            }

            var biggest: Long = 0
            for (di in deltas)
                biggest = Math.max(biggest, sizeOnDisk(di.update
                        .applied.size))

            requiredSpace += 3 * sizeOnDisk(biggest)
        }

        return requiredSpace
    }

    private fun findInitialFile(deltas: List<DeltaInfo>,
                                possibleMatch: String?, needsProcessing: BooleanArray?): String? {
        // Find the currently flashed ZIP
        Logger.d("findInitialFile possibleMatch = " + possibleMatch!!)

        val firstDelta = deltas[0]

        updateState(STATE_ACTION_SEARCHING, null, null, null, null, null)

        var initialFile: String? = null

        // Check if an original flashable ZIP is in our preferred location
        val expectedLocation = config!!.pathBase + firstDelta.`in`.name
        Logger.d("findInitialFile expectedLocation = $expectedLocation")
        var match: DeltaInfo.FileSizeMD5? = null
        if (expectedLocation == possibleMatch) {
            match = firstDelta.`in`.match(File(expectedLocation), false, null)
            if (match != null) {
                initialFile = possibleMatch
            }
        }

        if (match == null) {
            match = firstDelta.`in`.match(
                    File(expectedLocation),
                    true,
                    getMD5Progress(STATE_ACTION_SEARCHING_MD5, firstDelta
                            .`in`.name))
            if (match != null) {
                initialFile = expectedLocation
            }
        }

        if (needsProcessing != null && needsProcessing.size > 0) {
            needsProcessing[0] = initialFile != null && match !== firstDelta.`in`.store
        }

        return initialFile
    }

    private fun downloadFiles(deltas: List<DeltaInfo>, getFull: Boolean,
                              totalDownloadSize: Long, force: Boolean): Boolean {
        // Download all the files we do not have yet

        val lastDelta = deltas[deltas.size - 1]

        val filename = arrayOf<String>(null)
        updateState(STATE_ACTION_DOWNLOADING, 0f, 0L, totalDownloadSize, null, null)

        val last = longArrayOf(0, totalDownloadSize, 0, SystemClock.elapsedRealtime())
        val progressListener = object : DeltaInfo.ProgressListener {
            override fun onProgress(progress: Float, current: Long, total: Long) {
                var progress = progress
                var current = current
                var total = total
                current += last[0]
                total = last[1]
                progress = current.toFloat() / total.toFloat() * 100f
                val now = SystemClock.elapsedRealtime()
                if (now >= last[2] + 16L) {
                    updateState(STATE_ACTION_DOWNLOADING, progress, current,
                            total, filename[0], SystemClock.elapsedRealtime() - last[3])
                    last[2] = now
                }
            }

            override fun setStatus(s: String) {
                // do nothing
            }
        }

        if (getFull) {
            filename[0] = lastDelta.out.name
            if (!downloadDeltaFile(config!!.urlBaseFull, lastDelta.out,
                            lastDelta.out.official, progressListener, force)) {
                return false
            }
        } else {
            for (di in deltas) {
                filename[0] = di.update.name
                if (!downloadDeltaFile(config!!.urlBaseUpdate,
                                di.update, di.update.update,
                                progressListener, force)) {
                    return false
                }
                last[0] += di.update.update.size
            }

            if (config!!.applySignature) {
                filename[0] = lastDelta.signature.name
                if (!downloadDeltaFile(config!!.urlBaseUpdate,
                                lastDelta.signature, lastDelta.signature
                                .update, progressListener, force)) {
                    return false
                }
            }
        }
        updateState(STATE_ACTION_DOWNLOADING, 100f, totalDownloadSize,
                totalDownloadSize, null, null)

        return true
    }

    private fun downloadFullBuild(url: String, md5Sum: String?,
                                  imageName: String?): Boolean {
        val filename = arrayOf<String>(null)
        filename[0] = imageName
        val fn = config!!.pathBase + imageName!!
        val f = File(fn)
        Logger.d("download: %s --> %s", url, fn)

        if (downloadUrlFileUnknownSize(url, f, md5Sum)) {
            Logger.d("success")
            prefs!!.edit().putString(PREF_READY_FILENAME_NAME, fn).commit()
        } else {
            f.delete()
            if (stopDownload) {
                Logger.d("download stopped")
            } else {
                Logger.d("download error")
                updateState(STATE_ERROR_DOWNLOAD, null, null, null, url, null)
            }
        }

        return true
    }

    private fun checkFullBuildMd5Sum(url: String, fn: String): Boolean {
        val md5Url = "$url.md5sum"
        val latestFullMd5 = downloadUrlMemoryAsString(md5Url)
        if (latestFullMd5 != null) {
            try {
                val md5Part = latestFullMd5.split("  ".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[0]
                val fileMd5 = getFileMD5(File(fn), getMD5Progress(STATE_ACTION_CHECKING_MD5, File(fn).name))
                if (md5Part == fileMd5) {
                    return true
                }
            } catch (e: Exception) {
                // WTH knows what can comes from the server
            }

        }
        return false
    }

    private fun applyPatches(deltas: List<DeltaInfo>, initialFile: String?,
                             initialFileNeedsProcessing: Boolean): Boolean {
        // Create storeSigned outfile from infile + deltas

        val firstDelta = deltas[0]
        val lastDelta = deltas[deltas.size - 1]

        var tempFile = 0
        val tempFiles = arrayOf(config!!.pathBase + "temp1", config!!.pathBase + "temp2")
        try {
            val start = SystemClock.elapsedRealtime()
            var current = 0L
            var total = 0L

            if (initialFileNeedsProcessing)
                total += firstDelta.`in`.store.size
            for (di in deltas)
                total += di.update.applied.size
            if (config!!.applySignature)
                total += lastDelta.signature.applied.size

            if (initialFileNeedsProcessing) {
                if (!zipadjust(initialFile, tempFiles[tempFile], start,
                                current, total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null, null)
                    Logger.d("zipadjust error")
                    return false
                }
                tempFile = (tempFile + 1) % 2
                current += firstDelta.`in`.store.size
            }

            for (di in deltas) {
                var inFile: String? = tempFiles[(tempFile + 1) % 2]
                if (!initialFileNeedsProcessing && di === firstDelta)
                    inFile = initialFile
                var outFile = tempFiles[tempFile]
                if (!config!!.applySignature && di === lastDelta)
                    outFile = config!!.pathBase + lastDelta.out.name

                if (!dedelta(inFile, config!!.pathBase + di.update.name, outFile, start, current,
                                total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null, null)
                    Logger.d("dedelta error")
                    return false
                }
                tempFile = (tempFile + 1) % 2
                current += di.update.applied.size
            }

            if (config!!.applySignature) {
                if (!dedelta(tempFiles[(tempFile + 1) % 2],
                                config!!.pathBase + lastDelta.signature.name,
                                config!!.pathBase + lastDelta.out.name,
                                start, current, total)) {
                    updateState(STATE_ERROR_UNKNOWN, null, null, null, null, null)
                    Logger.d("dedelta error")
                    return false
                }
                tempFile = (tempFile + 1) % 2
                current += lastDelta.signature.applied.size
            }
        } finally {
            File(tempFiles[0]).delete()
            File(tempFiles[1]).delete()
        }

        return true
    }

    @Throws(UnsupportedEncodingException::class, IOException::class)
    private fun writeString(os: OutputStream, s: String) {
        os.write((s + "\n").toByteArray(charset("UTF-8")))
    }

    @Throws(FileNotFoundException::class)
    private fun handleUpdateCleanup(): String {
        val flashFilename = prefs!!.getString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT)
        val intialFile = prefs!!.getString(PREF_INITIAL_FILE, PREF_READY_FILENAME_DEFAULT)

        if (flashFilename === PREF_READY_FILENAME_DEFAULT
                || !flashFilename!!.startsWith(config!!.pathBase)
                || !File(flashFilename).exists()) {
            throw FileNotFoundException("flashUpdate - no valid file to flash found " + flashFilename!!)
        }
        // now delete the initial file
        if (intialFile != null
                && File(intialFile).exists()
                && intialFile.startsWith(config!!.pathBase)) {
            File(intialFile).delete()
            Logger.d("flashUpdate - delete initial file")
        }

        return flashFilename
    }

    fun onUpdateCompleted(status: Int) {
        stopNotification()
        if (status == UpdateEngine.ErrorCodeConstants.SUCCESS) {
            val flashFilename = prefs!!.getString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT)
            if (flashFilename != PREF_READY_FILENAME_DEFAULT) {
                deleteOldFlashFile(flashFilename)
                prefs!!.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit()
            }
            startABRebootNotification()
            updateState(STATE_ACTION_AB_FINISHED, null, null, null, null, null)
        } else {
            updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null)
        }
    }

    @Synchronized
    private fun setNotificationProgress(percent: Int, sec: Int) {
        if (!isProgressNotificationDismissed) {
            // max progress is 100%
            mBuilder!!.setProgress(100, percent, false)
            val sub: String
            if (percent > 0) {
                sub = String.format(Locale.ENGLISH,
                        getString(R.string.notify_eta_remaining),
                        percent, sec / 60, sec % 60)
            } else {
                sub = String.format(Locale.ENGLISH,
                        "%2d%%",
                        percent)
            }
            mBuilder!!.setSubText(sub)
            notificationManager!!.notify(
                    NOTIFICATION_UPDATE, mBuilder!!.build())
        }
    }

    private fun flashABUpdate() {
        Logger.d("flashABUpdate")
        var flashFilename = ""
        try {
            flashFilename = handleUpdateCleanup()
        } catch (ex: Exception) {
            Logger.ex(ex)
        }

        // Clear the Download size to hide while flashing
        prefs!!.edit().putLong(PREF_DOWNLOAD_SIZE, -1).commit()

        val _filename = config!!.filenameBase + ".zip"
        updateState(STATE_ACTION_AB_FLASH, 0f, 0L, 100L, _filename, null)

        isProgressNotificationDismissed = false
        progressUpdateStart = SystemClock.elapsedRealtime()

        mBuilder = Notification.Builder(this)
        mBuilder!!.setSmallIcon(R.drawable.stat_notify_update)
                .setContentTitle(getString(R.string.state_action_ab_flash))
                .setShowWhen(true)
                .setContentIntent(getNotificationIntent(false))
                .setDeleteIntent(progressNotificationIntent)
                .setContentText(_filename)

        setNotificationProgress(0, 0)

        try {
            val zipFile = ZipFile(flashFilename)
            val isABUpdate = ABUpdate.isABUpdate(zipFile)
            zipFile.close()
            if (isABUpdate) {
                val last = longArrayOf(0, SystemClock.elapsedRealtime())

                val listener = object : DeltaInfo.ProgressListener {
                    private var status: String? = null
                    override fun onProgress(progress: Float, current: Long, total: Long) {
                        val now = SystemClock.elapsedRealtime()
                        if (now >= last[0] + 16L) {
                            val ms = SystemClock.elapsedRealtime() - last[1]
                            val sec = ((total.toFloat() / current.toFloat() * ms.toFloat() - ms) / 1000f).toInt()
                            updateState(STATE_ACTION_AB_FLASH, progress, current, total, this.status,
                                    ms)
                            setNotificationProgress(progress.toInt(), sec)
                            last[0] = now
                        }
                    }

                    override fun setStatus(status: String) {
                        this.status = status
                    }
                }
                listener.setStatus(_filename)
                if (!ABUpdate.start(flashFilename, listener, this)) {
                    stopNotification()
                    updateState(STATE_ERROR_AB_FLASH, null, null, null, null, null)
                    throw Exception("Failed to start installer, please reboot!")
                }
            } else {
                throw Exception("Not an AB Update or Update already installing")
            }
        } catch (ex: Exception) {
            Logger.ex(ex)
        }

    }

    @SuppressLint("SdCardPath")
    private fun flashUpdate() {
        Logger.d("flashUpdate")
        if (packageManager.checkPermission(
                        PERMISSION_ACCESS_CACHE_FILESYSTEM, packageName) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point",
                    PERMISSION_ACCESS_CACHE_FILESYSTEM)
            return
        }

        if (packageManager.checkPermission(PERMISSION_REBOOT,
                        packageName) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point", PERMISSION_REBOOT)
            return
        }

        val deltaSignature = prefs!!.getBoolean(PREF_DELTA_SIGNATURE, false)
        var flashFilename = ""
        try {
            flashFilename = handleUpdateCleanup()
        } catch (ex: Exception) {
            Logger.ex(ex)
            return
        }

        deleteOldFlashFile(flashFilename)
        prefs!!.edit().putString(PREF_CURRENT_FILENAME_NAME, flashFilename).commit()
        clearState()

        // Remove the path to the storage from the filename, so we get a path
        // relative to the root of the storage
        val path_sd = Environment.getExternalStorageDirectory().toString() + File.separator
        flashFilename = flashFilename.substring(path_sd.length)

        // Find additional ZIPs to flash, strip path to sd
        val extras = config!!.flashAfterUpdateZIPs
        for (i in extras.indices) {
            extras[i] = extras[i].substring(path_sd.length)
        }
        Logger.d("flashUpdate - extra files to flash $extras")


        try {
            // TWRP - OpenRecoveryScript - the recovery will find the correct
            // storage root for the ZIPs, life is nice and easy.
            //
            // Optionally, we're injecting our own signature verification keys
            // and verifying against those. We place these keys in /cache
            // where only privileged apps can edit, contrary to the storage
            // location of the ZIP itself - anyone can modify the ZIP.
            // As such, flashing the ZIP without checking the whole-file
            // signature coming from a secure location would be a security
            // risk.
            run {
                if (config!!.injectSignatureEnable && deltaSignature) {
                    Logger.d("flashUpdate - create /cache/recovery/keys")

                    val os = FileOutputStream(
                            "/cache/recovery/keys", false)
                    try {
                        writeString(os, config!!.injectSignatureKeys)
                    } finally {
                        os.close()
                    }
                    setPermissions("/cache/recovery/keys", 420,
                            Process.myUid(), 2001 /* AID_CACHE */)
                }

                Logger.d("flashUpdate - create /cache/recovery/openrecoveryscript")

                val os = FileOutputStream(
                        "/cache/recovery/openrecoveryscript", false)
                try {
                    if (config!!.injectSignatureEnable && deltaSignature) {
                        writeString(os, "cmd cat /res/keys > /res/keys_org")
                        writeString(os,
                                "cmd cat /cache/recovery/keys > /res/keys")
                        writeString(os, "set tw_signed_zip_verify 1")
                        writeString(os,
                                String.format("install %s", flashFilename))
                        writeString(os, "set tw_signed_zip_verify 0")
                        writeString(os, "cmd cat /res/keys_org > /res/keys")
                        writeString(os, "cmd rm /res/keys_org")
                    } else {
                        writeString(os, "set tw_signed_zip_verify 0")
                        writeString(os,
                                String.format("install %s", flashFilename))
                    }

                    if (!config!!.secureModeCurrent) {
                        // any program could have placed these ZIPs, so ignore
                        // them in secure mode
                        for (file in extras) {
                            writeString(os, String.format("install %s", file))
                        }
                    }
                    writeString(os, "wipe cache")
                } finally {
                    os.close()
                }

                setPermissions("/cache/recovery/openrecoveryscript", 420,
                        Process.myUid(), 2001 /* AID_CACHE */)
            }

            // CWM - ExtendedCommand - provide paths to both internal and
            // external storage locations, it's nigh impossible to know in
            // practice which will be correct, not just because the internal
            // storage location varies based on the external storage being
            // present, but also because it's not uncommon for community-built
            // versions to have them reversed. It'll give some horrible looking
            // results, but it seems to continue installing even if one ZIP
            // fails and produce the wanted result. Better than nothing ...
            //
            // We don't generate a CWM script in secure mode, because it
            // doesn't support checking our custom signatures
            if (!config!!.secureModeCurrent) {
                Logger.d("flashUpdate - create /cache/recovery/extendedcommand")

                val os = FileOutputStream(
                        "/cache/recovery/extendedcommand", false)
                try {
                    writeString(os, String.format("install_zip(\"%s%s\");",
                            "/sdcard/", flashFilename))
                    writeString(os, String.format("install_zip(\"%s%s\");",
                            "/emmc/", flashFilename))
                    for (file in extras) {
                        writeString(os, String.format("install_zip(\"%s%s\");",
                                "/sdcard/", file))
                        writeString(os, String.format("install_zip(\"%s%s\");",
                                "/emmc/", file))
                    }
                    writeString(os,
                            "run_program(\"/sbin/busybox\", \"rm\", \"-rf\", \"/cache/*\");")
                } finally {
                    os.close()
                }

                setPermissions("/cache/recovery/extendedcommand", 420,
                        Process.myUid(), 2001 /* AID_CACHE */)
            } else {
                File("/cache/recovery/extendedcommand").delete()
            }

            Logger.d("flashUpdate - reboot to recovery")

            (getSystemService(Context.POWER_SERVICE) as PowerManager).rebootCustom(PowerManager.REBOOT_RECOVERY)
        } catch (e: Exception) {
            // We have failed to write something. There's not really anything
            // else to do at
            // at this stage than give up. No reason to crash though.
            Logger.ex(e)
            updateState(STATE_ERROR_FLASH, null, null, null, null, null)
        }

    }

    private fun updateAvailable(): Boolean {
        val latestFull = prefs!!.getString(UpdateService.PREF_LATEST_FULL_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT)
        val latestDelta = prefs!!.getString(UpdateService.PREF_LATEST_DELTA_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT)
        return latestFull !== PREF_READY_FILENAME_DEFAULT || latestDelta !== PREF_READY_FILENAME_DEFAULT
    }

    private fun getLatestFullMd5Sum(latestFullFetch: String): String? {
        val md5Url = "$latestFullFetch.md5sum"
        val latestFullMd5 = downloadUrlMemoryAsString(md5Url)
        if (latestFullMd5 != null) {
            var md5Part: String? = null
            try {
                md5Part = latestFullMd5.split("  ".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()[0]
            } catch (e: Exception) {
                md5Part = latestFullMd5
            }

            Logger.d("getLatestFullMd5Sum - md5sum = " + md5Part!!)
            return md5Part
        }
        return null
    }

    private fun getProgress(current: Long, total: Long): Float {
        return if (total == 0L) 0f else current.toFloat() / total.toFloat() * 100f
    }

    // need to locally here for the deltas == 0 case
    private fun getFileMD5(file: File, progressListener: ProgressListener?): String? {
        var ret: String? = null

        var current: Long = 0
        val total = file.length()
        progressListener?.onProgress(getProgress(current, total), current, total)

        try {
            val `is` = FileInputStream(file)
            try {
                val digest = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(256 * 1024)
                var r: Int

                while ((r = `is`.read(buffer)) > 0) {
                    digest.update(buffer, 0, r)
                    current += r.toLong()
                    progressListener?.onProgress(getProgress(current, total), current, total)
                }

                var MD5 = BigInteger(1, digest.digest()).toString(16).toLowerCase(Locale.ENGLISH)
                while (MD5.length < 32)
                    MD5 = "0$MD5"
                ret = MD5
            } finally {
                `is`.close()
            }
        } catch (e: NoSuchAlgorithmException) {
            // No MD5 support (returns null)
            Logger.ex(e)
        } catch (e: FileNotFoundException) {
            // The MD5 of a non-existing file is null
            Logger.ex(e)
        } catch (e: IOException) {
            // Read or close error (returns null)
            Logger.ex(e)
        }

        progressListener?.onProgress(getProgress(total, total), total, total)

        return ret
    }

    private fun clearState() {
        prefs!!.edit().putString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT).commit()
        prefs!!.edit().putString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT).commit()
        prefs!!.edit().putString(PREF_READY_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT).commit()
        prefs!!.edit().putLong(PREF_DOWNLOAD_SIZE, -1).commit()
        prefs!!.edit().putBoolean(PREF_DELTA_SIGNATURE, false).commit()
        prefs!!.edit().putString(PREF_INITIAL_FILE, PREF_READY_FILENAME_DEFAULT).commit()
    }

    private fun shouldShowErrorNotification() {
        val dailyAlarm = prefs!!.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART) == SettingsActivity.PREF_SCHEDULER_MODE_DAILY

        if (dailyAlarm || failedUpdateCount >= 4) {
            // if from scheduler show a notification cause user should
            // see that something went wrong
            // if we check only daily always show - if smart mode wait for 4
            // consecutive failure - would be about 24h
            startErrorNotification()
            failedUpdateCount = 0
        }
    }

    private fun checkForUpdatesAsync(userInitiated: Boolean, checkOnly: Int) {
        Logger.d("checkForUpdatesAsync " + prefs!!.all)

        updateState(STATE_ACTION_CHECKING, null, null, null, null, null)
        wakeLock!!.acquire()
        wifiLock!!.acquire()

        var notificationText = getString(R.string.state_action_checking)
        if (checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
            notificationText = getString(R.string.state_action_downloading)
        }
        val notification = Notification.Builder(this).setSmallIcon(R.drawable.stat_notify_update).setContentTitle(getString(R.string.title)).setContentText(notificationText).setShowWhen(false).setContentIntent(getNotificationIntent(false)).build()
        // TODO update notification with current step
        startForeground(NOTIFICATION_BUSY, notification)

        handler!!.post(object : Runnable {
            override fun run() {
                var downloadFullBuild = false

                stopDownload = false
                updateRunning = true

                try {
                    val deltas = ArrayList<DeltaInfo>()

                    var flashFilename: String? = null
                    File(config!!.pathBase).mkdir()
                    File(config!!.pathFlashAfterUpdate).mkdir()

                    clearState()

                    val latestFullBuild = newestFullBuild
                    // if we dont even find a build on dl no sense to continue
                    if (latestFullBuild == null) {
                        Logger.d("no latest build found at " + config!!.urlBaseJson +
                                " for " + config!!.device + " prefix " + config!!.fileBaseNamePrefix)
                        return
                    }

                    val latestFullFetch = String.format(Locale.ENGLISH, "%s%s",
                            config!!.urlBaseFull,
                            latestFullBuild)
                    Logger.d("latest full build for device " + config!!.device + " is " + latestFullFetch)
                    prefs!!.edit().putString(PREF_LATEST_FULL_NAME, latestFullBuild).commit()

                    // Create a list of deltas to apply to get from our current
                    // version to the latest
                    var fetch = String.format(Locale.ENGLISH, "%s%s.delta",
                            config!!.urlBaseDelta,
                            config!!.filenameBase)

                    while (true) {
                        var delta: DeltaInfo? = null
                        var data = downloadUrlMemory(fetch)
                        if (data != null && data.size != 0) {
                            try {
                                delta = DeltaInfo(data, false)
                            } catch (e: JSONException) {
                                // There's an error in the JSON. Could be bad JSON,
                                // could be a 404 text, etc
                                Logger.ex(e)
                                delta = null
                            } catch (e: NullPointerException) {
                                // Download failed
                                Logger.ex(e)
                                delta = null
                            }

                        }

                        if (delta == null) {
                            // See if we have a revoked version instead, we
                            // still need it for chaining future deltas, but
                            // will not allow flashing this one
                            data = downloadUrlMemory(fetch.replace(".delta",
                                    ".delta_revoked"))
                            if (data != null && data.size != 0) {
                                try {
                                    delta = DeltaInfo(data, true)
                                } catch (e: JSONException) {
                                    // There's an error in the JSON. Could be bad
                                    // JSON, could be a 404 text, etc
                                    Logger.ex(e)
                                    delta = null
                                } catch (e: NullPointerException) {
                                    // Download failed
                                    Logger.ex(e)
                                    delta = null
                                }

                            }

                            // We didn't get a delta or a delta_revoked - end of
                            // the delta availability chain
                            if (delta == null)
                                break
                        }

                        Logger.d("delta --> [%s]", delta.out.name)
                        fetch = String.format(Locale.ENGLISH, "%s%s.delta",
                                config!!.urlBaseDelta, delta
                                .out.name.replace(".zip", ""))
                        deltas.add(delta)
                    }

                    if (deltas.size > 0) {
                        // See if we have done past work and have newer ZIPs
                        // than the original of what's currently flashed

                        var last = -1
                        for (i in deltas.indices.reversed()) {
                            val di = deltas[i]
                            val fn = config!!.pathBase + di.out.name
                            if (di.out
                                            .match(File(fn),
                                                    true,
                                                    getMD5Progress(STATE_ACTION_CHECKING_MD5, di.out
                                                            .name)) != null) {
                                if (latestFullBuild == di.out.name) {
                                    val signedFile = di.out.isSignedFile(File(fn))
                                    Logger.d("match found (%s): %s", if (signedFile) "delta" else "full", di.out.name)
                                    flashFilename = fn
                                    last = i
                                    prefs!!.edit().putBoolean(PREF_DELTA_SIGNATURE, signedFile).commit()
                                    break
                                }
                            }
                        }

                        if (last > -1) {
                            for (i in 0..last) {
                                deltas.removeAt(0)
                            }
                        }
                    }

                    while (deltas.size > 0 && deltas[deltas.size - 1].isRevoked) {
                        // Make sure the last delta is not revoked
                        deltas.removeAt(deltas.size - 1)
                    }

                    if (deltas.size == 0) {
                        // we found a matching zip created from deltas before
                        if (flashFilename != null) {
                            prefs!!.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit()
                            return
                        }
                        // only full download available
                        val latestFull = prefs!!.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT)
                        val latestFullZip = if (latestFull !== PREF_READY_FILENAME_DEFAULT) latestFull else null
                        val currentVersionZip = config!!.filenameBase + ".zip"

                        val updateAvilable = latestFullZip != null && java.lang.Long.parseLong(latestFullZip.replace("\\D+".toRegex(), "")) > java.lang.Long.parseLong(currentVersionZip.replace("\\D+".toRegex(), ""))
                        downloadFullBuild = updateAvilable

                        if (!updateAvilable) {
                            prefs!!.edit().putString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT).commit()
                        }

                        if (downloadFullBuild) {
                            val fn = config!!.pathBase + latestFullBuild
                            if (File(fn).exists()) {
                                if (checkFullBuildMd5Sum(latestFullFetch, fn)) {
                                    Logger.d("match found (full): $fn")
                                    prefs!!.edit().putString(PREF_READY_FILENAME_NAME, fn).commit()
                                    downloadFullBuild = false
                                } else {
                                    Logger.d("md5sum check failed : $fn")
                                }
                            }
                        }
                        if (updateAvilable && downloadFullBuild) {
                            val size = getUrlDownloadSize(latestFullFetch)
                            prefs!!.edit().putLong(PREF_DOWNLOAD_SIZE, size).commit()
                        }
                        Logger.d("check donne: latest full build available = " + prefs!!.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT) +
                                " : updateAvilable = " + updateAvilable + " : downloadFullBuild = " + downloadFullBuild)

                        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK) {
                            return
                        }
                    } else {
                        val lastDelta = deltas[deltas.size - 1]
                        flashFilename = config!!.pathBase + lastDelta.out.name

                        val deltaDownloadSize = getDeltaDownloadSize(deltas)
                        val fullDownloadSize = getFullDownloadSize(deltas)

                        Logger.d("download size --> deltas[%d] vs full[%d]", deltaDownloadSize,
                                fullDownloadSize)

                        // Find the currently flashed ZIP, or a newer one
                        var initialFile: String? = null
                        var initialFileNeedsProcessing = false
                        run({
                            val needsProcessing = booleanArrayOf(false)
                            initialFile = findInitialFile(deltas, flashFilename, needsProcessing)
                            initialFileNeedsProcessing = needsProcessing[0]
                        })
                        Logger.d("initial: %s", if (initialFile != null) initialFile else "not found")

                        // If we don't have a file to start out with, or the
                        // combined deltas get big, just get the latest full ZIP
                        var betterDownloadFullBuild = deltaDownloadSize > fullDownloadSize

                        val latestFull = prefs!!.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT)
                        val latestDelta = flashFilename

                        val latestDeltaZip = if (latestDelta !== PREF_READY_FILENAME_DEFAULT) File(latestDelta).name else null
                        val latestFullZip = if (latestFull !== PREF_READY_FILENAME_DEFAULT) latestFull else null
                        val currentVersionZip = config!!.filenameBase + ".zip"
                        val fullUpdatePossible = latestFullZip != null && java.lang.Long.parseLong(latestFullZip.replace("\\D+".toRegex(), "")) > java.lang.Long.parseLong(currentVersionZip.replace("\\D+".toRegex(), ""))
                        val deltaUpdatePossible = initialFile != null && latestDeltaZip != null && java.lang.Long.parseLong(latestDeltaZip.replace("\\D+".toRegex(), "")) > java.lang.Long.parseLong(currentVersionZip.replace("\\D+".toRegex(), "")) && latestDeltaZip == latestFullZip

                        // is the full version newer then what we could create with delta?
                        if (latestFullZip!!.compareTo(latestDeltaZip!!) > 0) {
                            betterDownloadFullBuild = true
                        }

                        Logger.d("latestDeltaZip = $latestDeltaZip currentVersionZip = $currentVersionZip latestFullZip = $latestFullZip")

                        Logger.d("deltaUpdatePossible = $deltaUpdatePossible fullUpdatePossible = $fullUpdatePossible betterDownloadFullBuild = $betterDownloadFullBuild")

                        if (!deltaUpdatePossible || betterDownloadFullBuild && fullUpdatePossible) {
                            downloadFullBuild = true
                        }
                        val updateAvilable = fullUpdatePossible || deltaUpdatePossible

                        if (!updateAvilable) {
                            prefs!!.edit().putString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT).commit()
                            prefs!!.edit().putString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT).commit()
                        } else {
                            if (downloadFullBuild) {
                                prefs!!.edit().putString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT).commit()
                            } else {
                                prefs!!.edit().putString(PREF_LATEST_DELTA_NAME, File(flashFilename).name).commit()
                            }
                        }

                        if (downloadFullBuild) {
                            val fn = config!!.pathBase + latestFullBuild
                            if (File(fn).exists()) {
                                if (checkFullBuildMd5Sum(latestFullFetch, fn)) {
                                    Logger.d("match found (full): $fn")
                                    prefs!!.edit().putString(PREF_READY_FILENAME_NAME, fn).commit()
                                    downloadFullBuild = false
                                } else {
                                    Logger.d("md5sum check failed : $fn")
                                }
                            }
                        }
                        if (updateAvilable) {
                            if (deltaUpdatePossible) {
                                prefs!!.edit().putLong(PREF_DOWNLOAD_SIZE, deltaDownloadSize).commit()
                            } else if (downloadFullBuild) {
                                prefs!!.edit().putLong(PREF_DOWNLOAD_SIZE, fullDownloadSize).commit()
                            }
                        }
                        Logger.d("check donne: latest valid delta update = " + prefs!!.getString(PREF_LATEST_DELTA_NAME, PREF_READY_FILENAME_DEFAULT) +
                                " : latest full build available = " + prefs!!.getString(PREF_LATEST_FULL_NAME, PREF_READY_FILENAME_DEFAULT) +
                                " : updateAvilable = " + updateAvilable + " : downloadFullBuild = " + downloadFullBuild)

                        val requiredSpace = getRequiredSpace(deltas, downloadFullBuild)
                        val freeSpace = StatFs(config!!.pathBase).availableBytes
                        Logger.d("requiredSpace = $requiredSpace freeSpace = $freeSpace")

                        if (freeSpace < requiredSpace) {
                            updateState(STATE_ERROR_DISK_SPACE, null, freeSpace, requiredSpace, null, null)
                            Logger.d("not enough space!")
                            return
                        }

                        if (checkOnly == PREF_AUTO_DOWNLOAD_CHECK) {
                            return
                        }
                        val downloadSize = if (downloadFullBuild) fullDownloadSize else deltaDownloadSize

                        if (!downloadFullBuild && checkOnly > PREF_AUTO_DOWNLOAD_CHECK) {
                            // Download all the files we do not have yet
                            // getFull = false since full download is handled below
                            if (!downloadFiles(deltas, false, downloadSize, userInitiated))
                                return

                            // Reconstruct flashable ZIP
                            if (!applyPatches(deltas, initialFile, initialFileNeedsProcessing))
                                return

                            // Verify using MD5
                            if (lastDelta.out.match(
                                            File(config!!.pathBase + lastDelta.out.name),
                                            true,
                                            getMD5Progress(STATE_ACTION_APPLYING_MD5, lastDelta.out
                                                    .name)) == null) {
                                updateState(STATE_ERROR_UNKNOWN, null, null, null, null, null)
                                Logger.d("final verification error")
                                return
                            }
                            Logger.d("final verification complete")

                            // Cleanup
                            for (di in deltas) {
                                File(config!!.pathBase + di.update.name).delete()
                                File(config!!.pathBase + di.signature.name).delete()
                                if (di !== lastDelta)
                                    File(config!!.pathBase + di.out.name).delete()
                            }
                            // we will not delete initialFile until flashing
                            // else people building images and not flashing for 24h will loose
                            // the possibility to do delta updates
                            if (initialFile != null) {
                                if (initialFile!!.startsWith(config!!.pathBase)) {
                                    prefs!!.edit().putString(PREF_INITIAL_FILE, initialFile).commit()
                                }
                            }
                            prefs!!.edit().putBoolean(PREF_DELTA_SIGNATURE, true).commit()
                            prefs!!.edit().putString(PREF_READY_FILENAME_NAME, flashFilename).commit()
                        }
                    }
                    if (downloadFullBuild && checkOnly == PREF_AUTO_DOWNLOAD_FULL) {
                        if (userInitiated || networkState!!.state!!) {
                            val latestFullMd5 = getLatestFullMd5Sum(latestFullFetch)
                            if (latestFullMd5 != null) {
                                downloadFullBuild(latestFullFetch, latestFullMd5, latestFullBuild)
                            } else {
                                Logger.d("aborting download due to md5sum not found")
                            }
                        } else {
                            Logger.d("aborting download due to network state")
                        }
                    }
                } finally {
                    prefs!!.edit().putLong(PREF_LAST_CHECK_TIME_NAME, System.currentTimeMillis()).commit()
                    stopForeground(true)
                    if (wifiLock!!.isHeld) wifiLock!!.release()
                    if (wakeLock!!.isHeld) wakeLock!!.release()

                    if (isErrorState(state)) {
                        failedUpdateCount++
                        clearState()
                        if (!userInitiated) {
                            shouldShowErrorNotification()
                        }
                    } else {
                        failedUpdateCount = 0
                        autoState(userInitiated, checkOnly, true)
                    }
                    updateRunning = false
                }
            }
        })
    }

    private fun checkPermissions(): Boolean {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("checkPermissions failed")
            updateState(STATE_ERROR_PERMISSIONS, null, null, null, null, null)
            return false
        }
        return true
    }

    private fun deleteOldFlashFile(newFlashFilename: String) {
        val oldFlashFilename = prefs!!.getString(PREF_CURRENT_FILENAME_NAME, PREF_READY_FILENAME_DEFAULT)
        Logger.d("delete oldFlashFilename $oldFlashFilename $newFlashFilename")

        if (oldFlashFilename !== PREF_READY_FILENAME_DEFAULT && oldFlashFilename != newFlashFilename
                && oldFlashFilename!!.startsWith(config!!.pathBase)) {
            val file = File(oldFlashFilename)
            if (file.exists() && file.name.startsWith(config!!.fileBaseNamePrefix)) {
                Logger.d("delete oldFlashFilename $oldFlashFilename")
                file.delete()
            }
        }
    }

    private fun scanImageFiles() {
        // for debugging purposes
        val dataFolder = config!!.pathBase
        val contents = File(dataFolder).listFiles()
        if (contents != null) {
            for (file in contents) {
                if (file.isFile && file.name.startsWith(config!!.fileBaseNamePrefix)) {
                    Logger.d("image file: " + file.name)
                }
            }
        }
    }

    companion object {
        private val HTTP_READ_TIMEOUT = 30000
        private val HTTP_CONNECTION_TIMEOUT = 30000

        fun start(context: Context) {
            start(context, null)
        }

        fun startCheck(context: Context) {
            start(context, ACTION_CHECK)
        }

        fun startFlash(context: Context) {
            start(context, ACTION_FLASH)
        }

        fun startBuild(context: Context) {
            start(context, ACTION_BUILD)
        }

        fun startUpdate(context: Context) {
            start(context, ACTION_UPDATE)
        }

        fun startClearRunningInstall(context: Context) {
            start(context, ACTION_CLEAR_INSTALL_RUNNING)
        }

        private fun start(context: Context, action: String?) {
            val i = Intent(context, UpdateService::class.java)
            i.action = action
            context.startService(i)
        }

        fun alarmPending(context: Context, id: Int): PendingIntent {
            val intent = Intent(context, UpdateService::class.java)
            intent.action = ACTION_ALARM
            intent.putExtra(EXTRA_ALARM_ID, id)
            return PendingIntent.getService(context, id, intent, 0)
        }

        val ACTION_SYSTEM_UPDATE_SETTINGS = "android.settings.SYSTEM_UPDATE_SETTINGS"
        val PERMISSION_ACCESS_CACHE_FILESYSTEM = "android.permission.ACCESS_CACHE_FILESYSTEM"
        val PERMISSION_REBOOT = "android.permission.REBOOT"

        val BROADCAST_INTENT = "eu.chainfire.opendelta.intent.BROADCAST_STATE"
        val EXTRA_STATE = "eu.chainfire.opendelta.extra.ACTION_STATE"
        val EXTRA_LAST_CHECK = "eu.chainfire.opendelta.extra.LAST_CHECK"
        val EXTRA_PROGRESS = "eu.chainfire.opendelta.extra.PROGRESS"
        val EXTRA_CURRENT = "eu.chainfire.opendelta.extra.CURRENT"
        val EXTRA_TOTAL = "eu.chainfire.opendelta.extra.TOTAL"
        val EXTRA_FILENAME = "eu.chainfire.opendelta.extra.FILENAME"
        val EXTRA_MS = "eu.chainfire.opendelta.extra.MS"

        val STATE_ACTION_NONE = "action_none"
        val STATE_ACTION_CHECKING = "action_checking"
        val STATE_ACTION_CHECKING_MD5 = "action_checking_md5"
        val STATE_ACTION_SEARCHING = "action_searching"
        val STATE_ACTION_SEARCHING_MD5 = "action_searching_md5"
        val STATE_ACTION_DOWNLOADING = "action_downloading"
        val STATE_ACTION_APPLYING = "action_applying"
        val STATE_ACTION_APPLYING_PATCH = "action_applying_patch"
        val STATE_ACTION_APPLYING_MD5 = "action_applying_md5"
        val STATE_ACTION_READY = "action_ready"
        val STATE_ACTION_AB_FLASH = "action_ab_flash"
        val STATE_ACTION_AB_FINISHED = "action_ab_finished"
        val STATE_ERROR_DISK_SPACE = "error_disk_space"
        val STATE_ERROR_UNKNOWN = "error_unknown"
        val STATE_ERROR_UNOFFICIAL = "error_unofficial"
        val STATE_ACTION_BUILD = "action_build"
        val STATE_ERROR_DOWNLOAD = "error_download"
        val STATE_ERROR_CONNECTION = "error_connection"
        val STATE_ERROR_PERMISSIONS = "error_permissions"
        val STATE_ERROR_FLASH = "error_flash"
        val STATE_ERROR_AB_FLASH = "error_ab_flash"

        private val ACTION_CHECK = "eu.chainfire.opendelta.action.CHECK"
        private val ACTION_FLASH = "eu.chainfire.opendelta.action.FLASH"
        private val ACTION_ALARM = "eu.chainfire.opendelta.action.ALARM"
        private val EXTRA_ALARM_ID = "eu.chainfire.opendelta.extra.ALARM_ID"
        private val ACTION_NOTIFICATION_DELETED = "eu.chainfire.opendelta.action.NOTIFICATION_DELETED"
        private val ACTION_BUILD = "eu.chainfire.opendelta.action.BUILD"
        private val ACTION_UPDATE = "eu.chainfire.opendelta.action.UPDATE"
        private val ACTION_PROGRESS_NOTIFICATION_DISMISSED = "eu.chainfire.opendelta.action.ACTION_PROGRESS_NOTIFICATION_DISMISSED"
        internal val ACTION_CLEAR_INSTALL_RUNNING = "eu.chainfire.opendelta.action.ACTION_CLEAR_INSTALL_RUNNING"

        private val NOTIFICATION_BUSY = 1
        private val NOTIFICATION_UPDATE = 2
        private val NOTIFICATION_ERROR = 3

        val PREF_READY_FILENAME_NAME = "ready_filename"
        val PREF_READY_FILENAME_DEFAULT: String? = null

        private val PREF_LAST_CHECK_TIME_NAME = "last_check_time"
        private val PREF_LAST_CHECK_TIME_DEFAULT = 0L

        private val PREF_LAST_SNOOZE_TIME_NAME = "last_snooze_time"
        private val PREF_LAST_SNOOZE_TIME_DEFAULT = 0L
        // we only snooze until a new build
        private val PREF_SNOOZE_UPDATE_NAME = "last_snooze_update"

        val PREF_CURRENT_FILENAME_NAME = "current_filename"

        private val SNOOZE_MS = 24 * AlarmManager.INTERVAL_HOUR

        val PREF_AUTO_UPDATE_NETWORKS_NAME = "auto_update_networks"
        val PREF_AUTO_UPDATE_NETWORKS_DEFAULT = NetworkState.ALLOW_WIFI or NetworkState.ALLOW_ETHERNET

        val PREF_LATEST_FULL_NAME = "latest_full_name"
        val PREF_LATEST_DELTA_NAME = "latest_delta_name"
        val PREF_STOP_DOWNLOAD = "stop_download"
        val PREF_DOWNLOAD_SIZE = "download_size_long"
        val PREF_DELTA_SIGNATURE = "delta_signature"
        val PREF_INITIAL_FILE = "initial_file"

        val PREF_AUTO_DOWNLOAD_DISABLED = 0
        val PREF_AUTO_DOWNLOAD_CHECK = 1
        val PREF_AUTO_DOWNLOAD_DELTA = 2
        val PREF_AUTO_DOWNLOAD_FULL = 3

        val PREF_AUTO_DOWNLOAD_CHECK_STRING = PREF_AUTO_DOWNLOAD_CHECK.toString()
        val PREF_AUTO_DOWNLOAD_DISABLED_STRING = PREF_AUTO_DOWNLOAD_DISABLED.toString()

        fun isProgressState(state: String): Boolean {
            return if (state == UpdateService.STATE_ACTION_DOWNLOADING ||
                    state == UpdateService.STATE_ACTION_SEARCHING ||
                    state == UpdateService.STATE_ACTION_SEARCHING_MD5 ||
                    state == UpdateService.STATE_ACTION_CHECKING ||
                    state == UpdateService.STATE_ACTION_CHECKING_MD5 ||
                    state == UpdateService.STATE_ACTION_APPLYING ||
                    state == UpdateService.STATE_ACTION_APPLYING_MD5 ||
                    state == UpdateService.STATE_ACTION_APPLYING_PATCH ||
                    state == UpdateService.STATE_ACTION_AB_FLASH) {
                true
            } else false
        }

        fun isErrorState(state: String): Boolean {
            return if (state == UpdateService.STATE_ERROR_DOWNLOAD ||
                    state == UpdateService.STATE_ERROR_DISK_SPACE ||
                    state == UpdateService.STATE_ERROR_UNKNOWN ||
                    state == UpdateService.STATE_ERROR_UNOFFICIAL ||
                    state == UpdateService.STATE_ERROR_CONNECTION) {
                true
            } else false
        }
    }
}
