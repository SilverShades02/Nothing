/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package eu.chainfire.opendelta

import java.io.File
import java.text.DateFormatSymbols
import java.util.ArrayList
import java.util.Arrays
import java.util.Calendar
import java.util.Locale

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.TimePickerDialog.OnTimeSetListener
import android.content.DialogInterface
import android.content.DialogInterface.OnClickListener
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceManager
import android.preference.PreferenceScreen
import android.text.Html
import android.text.format.DateFormat
import android.view.MenuItem
import android.widget.TimePicker
import android.widget.Toast

class SettingsActivity : PreferenceActivity(), OnPreferenceChangeListener, OnTimeSetListener {

    private var mNetworksConfig: Preference? = null
    private var mAutoDownload: ListPreference? = null
    private var mBatteryLevel: ListPreference? = null
    private var mChargeOnly: CheckBoxPreference? = null
    private var mSecureMode: CheckBoxPreference? = null
    private var mABPerfMode: CheckBoxPreference? = null
    private var mConfig: Config? = null
    private var mAutoDownloadCategory: PreferenceCategory? = null
    private var mSchedulerMode: ListPreference? = null
    private var mSchedulerDailyTime: Preference? = null
    private var mCleanFiles: Preference? = null
    private var mScheduleWeekDay: ListPreference? = null

    private val defaultAutoDownloadValue: String
        get() = if (isSupportedVersion) UpdateService.PREF_AUTO_DOWNLOAD_CHECK_STRING else UpdateService.PREF_AUTO_DOWNLOAD_DISABLED_STRING

    private val isSupportedVersion: Boolean
        get() = mConfig!!.isOfficialVersion

    private val weekdays: Array<String>
        get() {
            val dfs = DateFormatSymbols()
            val weekDayList = ArrayList<String>()
            weekDayList.addAll(Arrays.asList(*dfs.weekdays).subList(1, dfs.weekdays.size))
            return weekDayList.toTypedArray<String>()
        }

    public override fun onPause() {
        super.onPause()
    }

    public override fun onResume() {
        super.onResume()
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferenceManager
                .getDefaultSharedPreferences(this)
        mConfig = Config.getInstance(this)

        actionBar!!.setDisplayHomeAsUpEnabled(true)

        addPreferencesFromResource(R.xml.settings)
        mNetworksConfig = findPreference(KEY_NETWORKS) as Preference

        val autoDownload = prefs.getString(PREF_AUTO_DOWNLOAD, defaultAutoDownloadValue)
        val autoDownloadValue = Integer.valueOf(autoDownload).toInt()
        mAutoDownload = findPreference(PREF_AUTO_DOWNLOAD) as ListPreference
        mAutoDownload!!.onPreferenceChangeListener = this
        mAutoDownload!!.value = autoDownload
        mAutoDownload!!.summary = mAutoDownload!!.entry

        mBatteryLevel = findPreference(PREF_BATTERY_LEVEL) as ListPreference
        mBatteryLevel!!.onPreferenceChangeListener = this
        mBatteryLevel!!.summary = mBatteryLevel!!.entry
        mChargeOnly = findPreference(PREF_CHARGE_ONLY) as CheckBoxPreference
        mBatteryLevel!!.isEnabled = !prefs.getBoolean(PREF_CHARGE_ONLY, true)
        mSecureMode = findPreference(KEY_SECURE_MODE) as CheckBoxPreference
        mSecureMode!!.isEnabled = mConfig!!.secureModeEnable
        mSecureMode!!.isChecked = mConfig!!.secureModeCurrent
        mABPerfMode = findPreference(KEY_AB_PERF_MODE) as CheckBoxPreference
        mABPerfMode!!.isChecked = mConfig!!.abPerfModeCurrent
        mABPerfMode!!.onPreferenceChangeListener = this
        mAutoDownloadCategory = findPreference(KEY_CATEGORY_DOWNLOAD) as PreferenceCategory
        val flashingCategory = findPreference(KEY_CATEGORY_FLASHING) as PreferenceCategory

        if (!Config.isABDevice) {
            flashingCategory.removePreference(mABPerfMode)
        }

        mAutoDownloadCategory!!.isEnabled = autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_CHECK

        mSchedulerMode = findPreference(PREF_SCHEDULER_MODE) as ListPreference
        mSchedulerMode!!.onPreferenceChangeListener = this
        mSchedulerMode!!.summary = mSchedulerMode!!.entry
        mSchedulerMode!!.isEnabled = autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_DISABLED

        val schedulerMode = prefs.getString(PREF_SCHEDULER_MODE, PREF_SCHEDULER_MODE_SMART)
        mSchedulerDailyTime = findPreference(PREF_SCHEDULER_DAILY_TIME) as Preference
        mSchedulerDailyTime!!.isEnabled = schedulerMode != PREF_SCHEDULER_MODE_SMART
        mSchedulerDailyTime!!.summary = prefs.getString(
                PREF_SCHEDULER_DAILY_TIME, "00:00")

        mCleanFiles = findPreference(PREF_CLEAN_FILES) as Preference

        mScheduleWeekDay = findPreference(PREF_SCHEDULER_WEEK_DAY) as ListPreference
        mScheduleWeekDay!!.entries = weekdays
        mScheduleWeekDay!!.summary = mScheduleWeekDay!!.entry
        mScheduleWeekDay!!.onPreferenceChangeListener = this
        mScheduleWeekDay!!.isEnabled = schedulerMode == PREF_SCHEDULER_MODE_WEEKLY
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            else -> {
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPreferenceTreeClick(preferenceScreen: PreferenceScreen,
                                       preference: Preference): Boolean {

        if (preference === mNetworksConfig) {
            showNetworks()
            return true
        } else if (preference === mChargeOnly) {
            val value = preference.isChecked
            mBatteryLevel!!.isEnabled = !value
            return true
        } else if (preference === mSecureMode) {
            val value = preference.isChecked
            mConfig!!.secureModeCurrent = value
            AlertDialog.Builder(this)
                    .setTitle(
                            if (value)
                                R.string.secure_mode_enabled_title
                            else
                                R.string.secure_mode_disabled_title)
                    .setMessage(
                            Html.fromHtml(getString(if (value)
                                R.string.secure_mode_enabled_description
                            else
                                R.string.secure_mode_disabled_description)))
                    .setCancelable(true)
                    .setNeutralButton(android.R.string.ok, null).show()
            return true
        } else if (preference === mSchedulerDailyTime) {
            showTimePicker()
            return true
        } else if (preference === mCleanFiles) {
            val numDeletedFiles = cleanFiles()
            val prefs = PreferenceManager
                    .getDefaultSharedPreferences(this)
            clearState(prefs)
            prefs.edit().putBoolean(PREF_START_HINT_SHOWN, false).commit()
            Toast.makeText(this, String.format(getString(R.string.clean_files_feedback), numDeletedFiles), Toast.LENGTH_LONG).show()
            return true
        }
        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (preference === mAutoDownload) {
            val value = newValue as String
            val idx = mAutoDownload!!.findIndexOfValue(value)
            mAutoDownload!!.summary = mAutoDownload!!.entries[idx]
            mAutoDownload!!.setValueIndex(idx)
            val autoDownloadValue = Integer.valueOf(value).toInt()
            mAutoDownloadCategory!!.isEnabled = autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_CHECK
            mSchedulerMode!!.isEnabled = autoDownloadValue > UpdateService.PREF_AUTO_DOWNLOAD_DISABLED
            return true
        } else if (preference === mBatteryLevel) {
            val value = newValue as String
            val idx = mBatteryLevel!!.findIndexOfValue(value)
            mBatteryLevel!!.summary = mBatteryLevel!!.entries[idx]
            mBatteryLevel!!.setValueIndex(idx)
            return true
        } else if (preference === mSchedulerMode) {
            val value = newValue as String
            val idx = mSchedulerMode!!.findIndexOfValue(value)
            mSchedulerMode!!.summary = mSchedulerMode!!.entries[idx]
            mSchedulerMode!!.setValueIndex(idx)
            mSchedulerDailyTime!!.isEnabled = value != PREF_SCHEDULER_MODE_SMART
            mScheduleWeekDay!!.isEnabled = value == PREF_SCHEDULER_MODE_WEEKLY
            return true
        } else if (preference === mScheduleWeekDay) {
            val idx = mScheduleWeekDay!!.findIndexOfValue(newValue as String)
            mScheduleWeekDay!!.summary = mScheduleWeekDay!!.entries[idx]
            return true
        } else if (preference == mABPerfMode) {
            mConfig!!.abPerfModeCurrent = newValue as Boolean
            return true
        }
        return false
    }

    private fun showNetworks() {
        val prefs = PreferenceManager
                .getDefaultSharedPreferences(this)

        val flags = prefs.getInt(UpdateService.PREF_AUTO_UPDATE_NETWORKS_NAME,
                UpdateService.PREF_AUTO_UPDATE_NETWORKS_DEFAULT)
        val checkedItems = booleanArrayOf(flags and NetworkState.ALLOW_2G == NetworkState.ALLOW_2G, flags and NetworkState.ALLOW_3G == NetworkState.ALLOW_3G, flags and NetworkState.ALLOW_4G == NetworkState.ALLOW_4G, flags and NetworkState.ALLOW_WIFI == NetworkState.ALLOW_WIFI, flags and NetworkState.ALLOW_ETHERNET == NetworkState.ALLOW_ETHERNET, flags and NetworkState.ALLOW_UNKNOWN == NetworkState.ALLOW_UNKNOWN)

        AlertDialog.Builder(this)
                .setTitle(R.string.title_networks)
                .setMultiChoiceItems(
                        arrayOf<CharSequence>(getString(R.string.network_2g), getString(R.string.network_3g), getString(R.string.network_4g), getString(R.string.network_wifi), getString(R.string.network_ethernet), getString(R.string.network_unknown)),
                        checkedItems) { dialog, which, isChecked -> checkedItems[which] = isChecked }
                .setPositiveButton(android.R.string.ok) { dialog, which ->
                    var flags = 0
                    if (checkedItems[0])
                        flags += NetworkState.ALLOW_2G
                    if (checkedItems[1])
                        flags += NetworkState.ALLOW_3G
                    if (checkedItems[2])
                        flags += NetworkState.ALLOW_4G
                    if (checkedItems[3])
                        flags += NetworkState.ALLOW_WIFI
                    if (checkedItems[4])
                        flags += NetworkState.ALLOW_ETHERNET
                    if (checkedItems[5])
                        flags += NetworkState.ALLOW_UNKNOWN
                    prefs.edit()
                            .putInt(UpdateService.PREF_AUTO_UPDATE_NETWORKS_NAME,
                                    flags).commit()
                }.setNegativeButton(android.R.string.cancel, null)
                .setCancelable(true).show()
    }

    override fun onTimeSet(view: TimePicker, hourOfDay: Int, minute: Int) {
        val prefs = PreferenceManager
                .getDefaultSharedPreferences(this)
        val prefValue = String.format(Locale.ENGLISH, "%02d:%02d",
                hourOfDay, minute)
        prefs.edit().putString(PREF_SCHEDULER_DAILY_TIME, prefValue).commit()
        mSchedulerDailyTime!!.setSummary(prefValue)
    }

    private fun showTimePicker() {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val minute = c.get(Calendar.MINUTE)

        TimePickerDialog(this, this, hour, minute,
                DateFormat.is24HourFormat(this)).show()
    }

    private fun cleanFiles(): Int {
        var deletedFiles = 0
        val dataFolder = mConfig!!.pathBase
        val contents = File(dataFolder).listFiles()
        if (contents != null) {
            for (file in contents) {
                if (file.isFile && file.name.startsWith(mConfig!!.fileBaseNamePrefix)) {
                    file.delete()
                    deletedFiles++
                }
            }
        }
        return deletedFiles
    }

    private fun clearState(prefs: SharedPreferences) {
        prefs.edit().putString(UpdateService.PREF_LATEST_FULL_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT).commit()
        prefs.edit().putString(UpdateService.PREF_LATEST_DELTA_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT).commit()
        prefs.edit().putString(UpdateService.PREF_READY_FILENAME_NAME, UpdateService.PREF_READY_FILENAME_DEFAULT).commit()
        prefs.edit().putLong(UpdateService.PREF_DOWNLOAD_SIZE, -1).commit()
        prefs.edit().putBoolean(UpdateService.PREF_DELTA_SIGNATURE, false).commit()
        prefs.edit().putString(UpdateService.PREF_INITIAL_FILE, UpdateService.PREF_READY_FILENAME_DEFAULT).commit()
    }

    companion object {
        private val KEY_NETWORKS = "networks_config"
        val PREF_AUTO_DOWNLOAD = "auto_download_actions"
        val PREF_CHARGE_ONLY = "charge_only"
        val PREF_BATTERY_LEVEL = "battery_level_string"
        private val KEY_SECURE_MODE = "secure_mode"
        private val KEY_AB_PERF_MODE = "ab_perf_mode"
        private val KEY_CATEGORY_DOWNLOAD = "category_download"
        private val KEY_CATEGORY_FLASHING = "category_flashing"
        val PREF_SCREEN_STATE_OFF = "screen_state_off"
        private val PREF_CLEAN_FILES = "clear_files"
        val PREF_START_HINT_SHOWN = "start_hint_shown"

        val PREF_SCHEDULER_MODE = "scheduler_mode"
        val PREF_SCHEDULER_MODE_SMART = 0.toString()
        val PREF_SCHEDULER_MODE_DAILY = 1.toString()
        val PREF_SCHEDULER_MODE_WEEKLY = 2.toString()

        val PREF_SCHEDULER_DAILY_TIME = "scheduler_daily_time"
        val PREF_SCHEDULER_WEEK_DAY = "scheduler_week_day"
    }
}
