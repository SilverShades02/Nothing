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

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Environment
import android.os.SystemProperties
import android.preference.PreferenceManager

import java.io.File
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.Locale

class Config private constructor(context: Context) {

    private val prefs: SharedPreferences

    val version: String?
    val device: String?
    val filenameBase: String
    val pathBase: String
    val pathFlashAfterUpdate: String
    val urlBaseDelta: String
    val urlBaseUpdate: String
    val urlBaseFull: String
    val applySignature: Boolean
    private val inject_signature_enable: Boolean
    val injectSignatureKeys: String
    private val secure_mode_enable: Boolean
    private val secure_mode_default: Boolean
    val keepScreenOn: Boolean
    val fileBaseNamePrefix: String
    val urlBaseJson: String
    private val official_version_tag: String
    val androidVersion: String?
    private val weekly_version_tag: String
    private val security_version_tag: String

    // If we have full secure mode, let signature depend on secure mode
    // setting. If not, let signature depend on config setting only
    val injectSignatureEnable: Boolean
        get() = if (secureModeEnable) {
            secureModeCurrent
        } else {
            inject_signature_enable
        }

    val secureModeEnable: Boolean
        get() = applySignature && inject_signature_enable && secure_mode_enable

    val secureModeDefault: Boolean
        get() = secure_mode_default && secureModeEnable

    val secureModeCurrent: Boolean
        get() = secureModeEnable && prefs.getBoolean(PREF_SECURE_MODE_NAME,
                secureModeDefault)

    var abPerfModeCurrent: Boolean
        get() = prefs.getBoolean(PREF_AB_PERF_MODE_NAME, PREF_AB_PERF_MODE_DEFAULT)
        set(enable) {
            prefs.edit()
                    .putBoolean(PREF_AB_PERF_MODE_NAME, enable).commit()
        }

    val flashAfterUpdateZIPs: List<String>
        get() {
            val extras = ArrayList<String>()

            val files = File(pathFlashAfterUpdate).listFiles()
            if (files != null) {
                for (f in files) {
                    if (f.name.toLowerCase(Locale.ENGLISH).endsWith(".zip")) {
                        val filename = f.absolutePath
                        if (filename.startsWith(pathBase)) {
                            extras.add(filename)
                        }
                    }
                }
                Collections.sort(extras)
            }

            return extras
        }

    val shownRecoveryWarningSecure: Boolean
        get() = prefs.getBoolean(PREF_SHOWN_RECOVERY_WARNING_SECURE_NAME, false)

    val shownRecoveryWarningNotSecure: Boolean
        get() = prefs.getBoolean(PREF_SHOWN_RECOVERY_WARNING_NOT_SECURE_NAME,
                false)

    val isOfficialVersion: Boolean
        get() = version!!.indexOf(official_version_tag) != -1 ||
                version.indexOf(weekly_version_tag) != -1 ||
                version.indexOf(security_version_tag) != -1

    /*
     * Using reflection voodoo instead calling the hidden class directly, to
     * dev/test outside of AOSP tree
     */
    private fun getProperty(context: Context, key: String, defValue: String): String? {
        try {
            val SystemProperties = context.classLoader.loadClass(
                    "android.os.SystemProperties")
            val get = SystemProperties.getMethod("get", *arrayOf(String::class.java, String::class.java))
            return get.invoke(null, *arrayOf<Any>(key, defValue)) as String
        } catch (e: Exception) {
            // A lot of voodoo could go wrong here, return failure instead of
            // crash
            Logger.ex(e)
        }

        return null
    }

    init {
        prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val res = context.resources

        version = getProperty(context,
                res.getString(R.string.property_version), "")
        device = getProperty(context,
                res.getString(R.string.property_device), "")
        filenameBase = String.format(Locale.ENGLISH,
                res.getString(R.string.filename_base), version)

        pathBase = String.format(Locale.ENGLISH, "%s%s%s%s", Environment
                .getExternalStorageDirectory().absolutePath,
                File.separator, res.getString(R.string.path_base),
                File.separator)
        pathFlashAfterUpdate = String.format(Locale.ENGLISH, "%s%s%s",
                pathBase, "FlashAfterUpdate", File.separator)
        urlBaseDelta = String.format(Locale.ENGLISH,
                res.getString(R.string.url_base_delta), device)
        urlBaseUpdate = String.format(Locale.ENGLISH,
                res.getString(R.string.url_base_update), device)
        urlBaseFull = String.format(Locale.ENGLISH,
                res.getString(R.string.url_base_full), device)
        applySignature = res.getBoolean(R.bool.apply_signature)
        inject_signature_enable = res
                .getBoolean(R.bool.inject_signature_enable)
        injectSignatureKeys = res.getString(R.string.inject_signature_keys)
        secure_mode_enable = res.getBoolean(R.bool.secure_mode_enable)
        secure_mode_default = res.getBoolean(R.bool.secure_mode_default)
        urlBaseJson = res.getString(R.string.url_base_json)
        official_version_tag = res.getString(R.string.official_version_tag)
        weekly_version_tag = res.getString(R.string.weekly_version_tag)
        security_version_tag = res.getString(R.string.security_version_tag)
        androidVersion = getProperty(context,
                res.getString(R.string.android_version), "")
        fileBaseNamePrefix = String.format(Locale.ENGLISH,
                res.getString(R.string.filename_base), androidVersion)
        var keep_screen_on = false
        try {
            val devices = res
                    .getStringArray(R.array.keep_screen_on_devices)
            if (devices != null) {
                for (device in devices) {
                    if (this.device == device) {
                        keep_screen_on = true
                        break
                    }
                }
            }
        } catch (e: Resources.NotFoundException) {
        }

        this.keepScreenOn = keep_screen_on

        Logger.d("property_version: %s", version)
        Logger.d("property_device: %s", device)
        Logger.d("filename_base: %s", filenameBase)
        Logger.d("filename_base_prefix: %s", fileBaseNamePrefix)
        Logger.d("path_base: %s", pathBase)
        Logger.d("path_flash_after_update: %s", pathFlashAfterUpdate)
        Logger.d("url_base_delta: %s", urlBaseDelta)
        Logger.d("url_base_update: %s", urlBaseUpdate)
        Logger.d("url_base_full: %s", urlBaseFull)
        Logger.d("url_base_json: %s", urlBaseJson)
        Logger.d("apply_signature: %d", if (applySignature) 1 else 0)
        Logger.d("inject_signature_enable: %d", if (inject_signature_enable) 1 else 0)
        Logger.d("inject_signature_keys: %s", injectSignatureKeys)
        Logger.d("secure_mode_enable: %d", if (secure_mode_enable) 1 else 0)
        Logger.d("secure_mode_default: %d", if (secure_mode_default) 1 else 0)
        Logger.d("keep_screen_on: %d", if (keep_screen_on) 1 else 0)
    }

    fun setSecureModeCurrent(enable: Boolean): Boolean {
        prefs.edit()
                .putBoolean(PREF_SECURE_MODE_NAME,
                        secureModeEnable && enable).commit()
        return secureModeCurrent
    }

    fun setShownRecoveryWarningSecure() {
        prefs.edit().putBoolean(PREF_SHOWN_RECOVERY_WARNING_SECURE_NAME, true)
                .commit()
    }

    fun setShownRecoveryWarningNotSecure() {
        prefs.edit()
                .putBoolean(PREF_SHOWN_RECOVERY_WARNING_NOT_SECURE_NAME, true)
                .commit()
    }

    companion object {
        private var instance: Config? = null

        fun getInstance(context: Context): Config {
            if (instance == null) {
                instance = Config(context.applicationContext)
            }
            return instance
        }

        private val PREF_SECURE_MODE_NAME = "secure_mode"
        private val PREF_SHOWN_RECOVERY_WARNING_SECURE_NAME = "shown_recovery_warning_secure"
        private val PREF_SHOWN_RECOVERY_WARNING_NOT_SECURE_NAME = "shown_recovery_warning_not_secure"
        private val PREF_AB_PERF_MODE_NAME = "ab_perf_mode"
        private val PREF_AB_PERF_MODE_DEFAULT = false
        private val PROP_AB_DEVICE = "ro.build.ab_update"

        val isABDevice: Boolean
            get() = SystemProperties.getBoolean(PROP_AB_DEVICE, false)
    }
}
