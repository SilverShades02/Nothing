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

import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale

import android.Manifest
import android.app.Activity
import android.app.ActionBar
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnClickListener
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Bundle
import android.os.PowerManager
import android.preference.PreferenceManager
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.drawable.DrawableCompat
import android.text.Html
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
    private var title: TextView? = null
    private var sub: TextView? = null
    private var progress: ProgressBar? = null
    private var checkNow: Button? = null
    private var flashNow: Button? = null
    private var updateVersion: TextView? = null
    private var extra: TextView? = null
    private var buildNow: Button? = null
    private var stopNow: ImageButton? = null
    private var rebootNow: Button? = null
    private var currentVersion: TextView? = null
    private var lastChecked: TextView? = null
    private var lastCheckedHeader: TextView? = null
    private var downloadSizeHeader: TextView? = null
    private var downloadSize: TextView? = null
    private var config: Config? = null
    private var mPermOk: Boolean = false
    private var mSub2: TextView? = null
    private var mProgressPercent: TextView? = null
    private var mOmniLogo: ImageView? = null
    private var mProgressEndSpace: View? = null
    private var mProgressCurrent = 0
    private var mProgressMax = 1
    private var mProgressEnabled = false

    private val updateFilter = IntentFilter(
            UpdateService.BROADCAST_INTENT)
    private val updateReceiver = object : BroadcastReceiver() {
        private fun formatLastChecked(filename: String?, ms: Long): String {
            val date = Date(ms)
            return if (filename == null) {
                if (ms == 0L) {
                    ""
                } else {
                    getString(
                            R.string.last_checked,
                            DateFormat.getDateFormat(this@MainActivity).format(
                                    date),
                            DateFormat.getTimeFormat(this@MainActivity).format(
                                    date))
                }
            } else {
                if (ms == 0L) {
                    ""
                } else {
                    String.format(
                            "%s %s",
                            filename,
                            getString(R.string.last_checked,
                                    DateFormat.getDateFormat(this@MainActivity)
                                            .format(date), DateFormat
                                    .getTimeFormat(this@MainActivity)
                                    .format(date)))
                }
            }
        }

        override fun onReceive(context: Context, intent: Intent) {
            var title = ""
            var sub: String? = ""
            var sub2 = ""
            var progressPercent = ""
            var updateVersion = ""
            var lastCheckedText = ""
            var extraText = ""
            var downloadSizeText = ""
            var current = 0L
            var total = 1L
            var enableCheck = false
            var enableFlash = false
            var enableBuild = false
            var enableStop = false
            var enableReboot = false
            var deltaUpdatePossible = false
            var fullUpdatePossible = false
            var enableProgress = false
            var disableCheckNow = false
            var disableDataSpeed = false
            val prefs = PreferenceManager
                    .getDefaultSharedPreferences(this@MainActivity)

            val state = intent.getStringExtra(UpdateService.EXTRA_STATE)
            // don't try this at home
            if (state != null) {
                try {
                    title = getString(resources.getIdentifier(
                            "state_$state", "string", packageName))
                } catch (e: Exception) {
                    // String for this state could not be found (displays empty
                    // string)
                    //Logger.ex(e);
                }

                // check for first start until check button has been pressed
                // use a special title then - but only once
                if (UpdateService.STATE_ACTION_NONE == state && !prefs.getBoolean(SettingsActivity.PREF_START_HINT_SHOWN, false)) {
                    title = getString(R.string.last_checked_never_title_new)
                }
                // dont spill for progress
                if (!UpdateService.isProgressState(state)) {
                    Logger.d("onReceive state = $state")
                }
            }

            if (UpdateService.STATE_ERROR_DISK_SPACE == state) {
                enableCheck = true
                progress!!.isIndeterminate = false
                current = intent.getLongExtra(UpdateService.EXTRA_CURRENT,
                        current)
                total = intent.getLongExtra(UpdateService.EXTRA_TOTAL, total)

                current /= 1024L * 1024L
                total /= 1024L * 1024L

                extraText = getString(R.string.error_disk_space_sub, current,
                        total)
                DrawableCompat.setTint(mOmniLogo!!.drawable, ContextCompat.getColor(context, R.color.logo_err))
            } else if (UpdateService.STATE_ERROR_UNKNOWN == state) {
                enableCheck = true
                progress!!.isIndeterminate = false
            } else if (UpdateService.STATE_ERROR_UNOFFICIAL == state) {
                enableCheck = true
                progress!!.isIndeterminate = false
                title = getString(R.string.state_error_not_official_title)
                extraText = getString(R.string.state_error_not_official_extra,
                        intent.getStringExtra(UpdateService.EXTRA_FILENAME))
                DrawableCompat.setTint(mOmniLogo!!.drawable, ContextCompat.getColor(context, R.color.logo_disabled))
            } else if (UpdateService.STATE_ERROR_DOWNLOAD == state) {
                enableCheck = true
                progress!!.isIndeterminate = false
                extraText = intent.getStringExtra(UpdateService.EXTRA_FILENAME)
                DrawableCompat.setTint(mOmniLogo!!.drawable, ContextCompat.getColor(context, R.color.logo_err))
            } else if (UpdateService.STATE_ERROR_CONNECTION == state) {
                enableCheck = true
                progress!!.isIndeterminate = false
            } else if (UpdateService.STATE_ERROR_PERMISSIONS == state) {
                progress!!.isIndeterminate = false
            } else if (UpdateService.STATE_ERROR_FLASH == state) {
                enableCheck = true
                enableFlash = true
                progress!!.isIndeterminate = false
            } else if (UpdateService.STATE_ERROR_AB_FLASH == state) {
                enableCheck = true
                enableReboot = true
                progress!!.isIndeterminate = false
            } else if (UpdateService.STATE_ACTION_NONE == state) {
                enableCheck = true
                progress!!.isIndeterminate = false
                lastCheckedText = formatLastChecked(null,
                        intent.getLongExtra(UpdateService.EXTRA_MS, 0))
            } else if (UpdateService.STATE_ACTION_READY == state) {
                enableCheck = true
                enableFlash = true
                progress!!.isIndeterminate = false
                lastCheckedText = formatLastChecked(null,
                        intent.getLongExtra(UpdateService.EXTRA_MS, 0))

                val flashImage = prefs.getString(
                        UpdateService.PREF_READY_FILENAME_NAME,
                        UpdateService.PREF_READY_FILENAME_DEFAULT)
                val flashImageBase = if (flashImage !== UpdateService.PREF_READY_FILENAME_DEFAULT)
                    File(
                            flashImage!!).name
                else
                    null
                if (flashImageBase != null) {
                    updateVersion = flashImageBase.substring(0,
                            flashImageBase.lastIndexOf('.'))
                }
            } else if (UpdateService.STATE_ACTION_AB_FINISHED == state) {
                enableReboot = true
                disableCheckNow = true
                progress!!.isIndeterminate = false

                val flashImage = prefs.getString(
                        UpdateService.PREF_READY_FILENAME_NAME,
                        UpdateService.PREF_READY_FILENAME_DEFAULT)
                val flashImageBase = if (flashImage !== UpdateService.PREF_READY_FILENAME_DEFAULT)
                    File(
                            flashImage!!).name
                else
                    null
                if (flashImageBase != null) {
                    updateVersion = flashImageBase.substring(0,
                            flashImageBase.lastIndexOf('.'))
                }
            } else if (UpdateService.STATE_ACTION_BUILD == state) {
                enableCheck = true
                progress!!.isIndeterminate = false
                lastCheckedText = formatLastChecked(null,
                        intent.getLongExtra(UpdateService.EXTRA_MS, 0))

                val latestFull = prefs.getString(
                        UpdateService.PREF_LATEST_FULL_NAME,
                        UpdateService.PREF_READY_FILENAME_DEFAULT)
                val latestDelta = prefs.getString(
                        UpdateService.PREF_LATEST_DELTA_NAME,
                        UpdateService.PREF_READY_FILENAME_DEFAULT)

                val latestDeltaZip = if (latestDelta !== UpdateService.PREF_READY_FILENAME_DEFAULT)
                    File(
                            latestDelta!!).name
                else
                    null
                val latestFullZip = if (latestFull !== UpdateService.PREF_READY_FILENAME_DEFAULT)
                    latestFull
                else
                    null

                deltaUpdatePossible = latestDeltaZip != null
                fullUpdatePossible = latestFullZip != null
                DrawableCompat.setTint(mOmniLogo!!.drawable, ContextCompat.getColor(context, R.color.logo_green))


                if (deltaUpdatePossible) {
                    val latestDeltaBase = latestDelta!!.substring(0,
                            latestDelta.lastIndexOf('.'))
                    enableBuild = true
                    updateVersion = latestDeltaBase
                    title = getString(R.string.state_action_build_delta)
                    DrawableCompat.setTint(mOmniLogo!!.drawable, ContextCompat.getColor(context, R.color.logo_green))
                } else if (fullUpdatePossible) {
                    val latestFullBase = latestFull!!.substring(0,
                            latestFull.lastIndexOf('.'))
                    enableBuild = true
                    updateVersion = latestFullBase
                    title = getString(R.string.state_action_build_full)
                    DrawableCompat.setTint(mOmniLogo!!.drawable, ContextCompat.getColor(context, R.color.logo_green))
                }
                val downloadSize = prefs.getLong(
                        UpdateService.PREF_DOWNLOAD_SIZE, -1)
                if (downloadSize == -1) {
                    downloadSizeText = ""
                } else if (downloadSize == 0L) {
                    downloadSizeText = getString(R.string.text_download_size_unknown)
                } else {
                    downloadSizeText = Formatter.formatFileSize(context, downloadSize)
                }
            } else if (UpdateService.STATE_ACTION_SEARCHING == state || UpdateService.STATE_ACTION_CHECKING == state) {
                enableProgress = true
                progress!!.isIndeterminate = true
                current = 1
            } else {
                enableProgress = true
                if (UpdateService.STATE_ACTION_AB_FLASH == state) {
                    disableCheckNow = true
                    disableDataSpeed = true
                }
                if (UpdateService.STATE_ACTION_DOWNLOADING == state) {
                    enableStop = true
                }
                current = intent.getLongExtra(UpdateService.EXTRA_CURRENT,
                        current)
                total = intent.getLongExtra(UpdateService.EXTRA_TOTAL, total)
                progress!!.isIndeterminate = false

                val downloadSize = prefs.getLong(
                        UpdateService.PREF_DOWNLOAD_SIZE, -1)
                if (downloadSize == -1) {
                    downloadSizeText = ""
                } else if (downloadSize == 0L) {
                    downloadSizeText = getString(R.string.text_download_size_unknown)
                } else {
                    downloadSizeText = Formatter.formatFileSize(context, downloadSize)
                }

                val flashImage = prefs.getString(
                        UpdateService.PREF_READY_FILENAME_NAME,
                        UpdateService.PREF_READY_FILENAME_DEFAULT)
                val flashImageBase = if (flashImage !== UpdateService.PREF_READY_FILENAME_DEFAULT)
                    File(
                            flashImage!!).name
                else
                    null
                if (flashImageBase != null) {
                    updateVersion = flashImageBase.substring(0,
                            flashImageBase.lastIndexOf('.'))
                }

                // long --> int overflows FTL (progress.setXXX)
                var progressInK = false
                if (total > 1024L * 1024L * 1024L) {
                    progressInK = true
                    current /= 1024L
                    total /= 1024L
                }

                val filename = intent
                        .getStringExtra(UpdateService.EXTRA_FILENAME)
                if (filename != null) {
                    sub = filename
                    val ms = intent.getLongExtra(UpdateService.EXTRA_MS, 0)
                    progressPercent = String.format(Locale.ENGLISH, "%.0f %%",
                            intent.getFloatExtra(UpdateService.EXTRA_PROGRESS, 0f))

                    if (ms > 500 && current > 0 && total > 0) {
                        var kibps = current.toFloat() / 1024f / (ms.toFloat() / 1000f)
                        if (progressInK)
                            kibps *= 1024f
                        val sec = ((total.toFloat() / current.toFloat() * ms.toFloat() - ms) / 1000f).toInt()
                        if (disableDataSpeed) {
                            sub2 = String.format(Locale.ENGLISH,
                                    "%02d:%02d",
                                    sec / 60, sec % 60)
                        } else {
                            if (kibps < 10000) {
                                sub2 = String.format(Locale.ENGLISH,
                                        "%.0f KiB/s, %02d:%02d",
                                        kibps, sec / 60, sec % 60)
                            } else {
                                sub2 = String.format(Locale.ENGLISH,
                                        "%.0f MiB/s, %02d:%02d",
                                        kibps / 1024f, sec / 60, sec % 60)
                            }
                        }
                    }
                }
            }
            this@MainActivity.title!!.text = title
            this@MainActivity.sub!!.text = sub
            this@MainActivity.mSub2!!.text = sub2
            this@MainActivity.mProgressPercent!!.text = progressPercent
            this@MainActivity.updateVersion!!.text = updateVersion
            this@MainActivity.currentVersion!!.text = config!!.filenameBase
            this@MainActivity.lastChecked!!.text = lastCheckedText
            this@MainActivity.lastCheckedHeader!!.text = if (lastCheckedText == "") "" else getString(R.string.text_last_checked_header_title)
            this@MainActivity.extra!!.text = extraText
            this@MainActivity.lastCheckedHeader!!.text = if (lastCheckedText == "") "" else getString(R.string.text_last_checked_header_title)
            this@MainActivity.downloadSize!!.text = downloadSizeText
            this@MainActivity.downloadSizeHeader!!.text = if (downloadSizeText == "") "" else getString(R.string.text_download_size_header_title)

            mProgressCurrent = current.toInt()
            mProgressMax = total.toInt()
            mProgressEnabled = enableProgress

            handleProgressBar()

            checkNow!!.isEnabled = if (mPermOk && enableCheck) true else false
            buildNow!!.isEnabled = if (mPermOk && enableBuild) true else false
            flashNow!!.isEnabled = if (mPermOk && enableFlash) true else false
            rebootNow!!.isEnabled = if (enableReboot) true else false

            checkNow!!.visibility = if (disableCheckNow) View.GONE else View.VISIBLE
            flashNow!!.visibility = if (enableFlash) View.VISIBLE else View.GONE
            buildNow!!.visibility = if (!enableBuild || enableFlash)
                View.GONE
            else
                View.VISIBLE
            stopNow!!.visibility = if (enableStop) View.VISIBLE else View.GONE
            rebootNow!!.visibility = if (enableReboot) View.VISIBLE else View.GONE
            mProgressEndSpace!!.visibility = if (enableStop) View.VISIBLE else View.GONE
        }
    }

    private val flashRecoveryWarning = Runnable {
        // Show a warning message about recoveries we support, depending
        // on the state of secure mode and if we've shown the message before

        val next = flashWarningFlashAfterUpdateZIPs

        var message: CharSequence? = null
        if (!config!!.secureModeCurrent && !config!!.shownRecoveryWarningNotSecure) {
            message = Html
                    .fromHtml(getString(R.string.recovery_notice_description_not_secure))
            config!!.setShownRecoveryWarningNotSecure()
        } else if (config!!.secureModeCurrent && !config!!.shownRecoveryWarningSecure) {
            message = Html
                    .fromHtml(getString(R.string.recovery_notice_description_secure))
            config!!.setShownRecoveryWarningSecure()
        }

        if (message != null) {
            AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.recovery_notice_title)
                    .setMessage(message)
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok
                    ) { dialog, which -> next.run() }.show()
        } else {
            next.run()
        }
    }

    private val flashWarningFlashAfterUpdateZIPs: Runnable = Runnable {
        // If we're in secure mode, but additional ZIPs to flash have been
        // detected, warn the user that these will not be flashed

        val next = flashStart

        if (config!!.secureModeCurrent && config!!.flashAfterUpdateZIPs.size > 0) {
            AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.flash_after_update_notice_title)
                    .setMessage(
                            Html.fromHtml(getString(R.string.flash_after_update_notice_description)))
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok
                    ) { dialog, which -> next.run() }.show()
        } else {
            next.run()
        }
    }

    private val flashStart: Runnable = Runnable {
        checkNow!!.isEnabled = false
        flashNow!!.isEnabled = false
        buildNow!!.isEnabled = false
        UpdateService.startFlash(this@MainActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            actionBar!!.setIcon(
                    packageManager.getApplicationIcon(
                            "com.android.settings"))
        } catch (e: NameNotFoundException) {
            // The standard Settings package is not present, so we can't snatch
            // its icon
            Logger.ex(e)
        }

        actionBar!!.displayOptions = ActionBar.DISPLAY_HOME_AS_UP or ActionBar.DISPLAY_SHOW_TITLE

        UpdateService.start(this)

        setContentView(R.layout.activity_main)

        title = findViewById<View>(R.id.text_title) as TextView
        sub = findViewById<View>(R.id.progress_text) as TextView
        mSub2 = findViewById<View>(R.id.progress_text2) as TextView
        progress = findViewById<View>(R.id.progress_bar) as ProgressBar
        checkNow = findViewById<View>(R.id.button_check_now) as Button
        flashNow = findViewById<View>(R.id.button_flash_now) as Button
        rebootNow = findViewById<View>(R.id.button_reboot_now) as Button
        updateVersion = findViewById<View>(R.id.text_update_version) as TextView
        extra = findViewById<View>(R.id.text_extra) as TextView
        buildNow = findViewById<View>(R.id.button_build_delta) as Button
        stopNow = findViewById<View>(R.id.button_stop) as ImageButton
        currentVersion = findViewById<View>(R.id.text_current_version) as TextView
        lastChecked = findViewById<View>(R.id.text_last_checked) as TextView
        lastCheckedHeader = findViewById<View>(R.id.text_last_checked_header) as TextView
        downloadSize = findViewById<View>(R.id.text_download_size) as TextView
        downloadSizeHeader = findViewById<View>(R.id.text_download_size_header) as TextView
        mProgressPercent = findViewById<View>(R.id.progress_percent) as TextView
        mOmniLogo = findViewById<View>(R.id.omni_logo) as ImageView
        mProgressEndSpace = findViewById(R.id.progress_end_margin)

        config = Config.getInstance(this)
        mPermOk = false
        requestPermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    private fun showAbout() {
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val opendelta = if (thisYear == 2013)
            "2013"
        else
            "2013-" + thisYear.toString()
        val xdelta = if (thisYear == 1997)
            "1997"
        else
            "1997-" + thisYear.toString()

        val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(
                        Html.fromHtml(getString(R.string.about_content)
                                .replace("_COPYRIGHT_OPENDELTA_", opendelta)
                                .replace("_COPYRIGHT_XDELTA_", xdelta)))
                .setNeutralButton(android.R.string.ok, null)
                .setCancelable(true).show()
        val textView = dialog
                .findViewById<View>(android.R.id.message) as TextView
        if (textView != null)
            textView.typeface = title!!.typeface
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.settings -> {
                val settingsActivity = Intent(this, SettingsActivity::class.java)
                startActivity(settingsActivity)
                return true
            }
            R.id.action_about -> {
                showAbout()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(updateReceiver, updateFilter)
    }

    override fun onStop() {
        unregisterReceiver(updateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        handleProgressBar()
    }

    fun onButtonCheckNowClick(v: View) {
        val prefs = PreferenceManager
                .getDefaultSharedPreferences(this)
        prefs.edit().putBoolean(SettingsActivity.PREF_START_HINT_SHOWN, true).commit()
        UpdateService.startCheck(this)
    }

    fun onButtonRebootNowClick(v: View) {
        if (packageManager.checkPermission(UpdateService.PERMISSION_REBOOT,
                        packageName) != PackageManager.PERMISSION_GRANTED) {
            Logger.d("[%s] required beyond this point", UpdateService.PERMISSION_REBOOT)
            return
        }

        (getSystemService(Context.POWER_SERVICE) as PowerManager).rebootCustom(null)
    }

    fun onButtonBuildNowClick(v: View) {
        UpdateService.startBuild(this)
    }

    fun onButtonFlashNowClick(v: View) {
        if (Config.isABDevice) {
            flashStart.run()
        } else {
            flashRecoveryWarning.run()
        }
    }

    fun onButtonStopClick(v: View) {
        stopDownload()
    }

    private fun stopDownload() {
        val prefs = PreferenceManager
                .getDefaultSharedPreferences(this)
        prefs.edit()
                .putBoolean(
                        UpdateService.PREF_STOP_DOWNLOAD,
                        !prefs.getBoolean(UpdateService.PREF_STOP_DOWNLOAD,
                                false)).commit()
    }

    private fun requestPermissions() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE)
        } else {
            mPermOk = true
        }
    }

    private fun handleProgressBar() {
        progress!!.progress = mProgressCurrent
        progress!!.max = mProgressMax
        progress!!.visibility = if (mProgressEnabled) View.VISIBLE else View.INVISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mPermOk = true
                }
            }
        }
    }

    companion object {

        private val PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 0
    }
}
