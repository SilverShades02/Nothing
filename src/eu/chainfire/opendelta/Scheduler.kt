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
/*
 * We're using three different alarms for scheduling. The primary is an
 * (inexact) interval alarm that is fired every 30-60 minutes (if the device 
 * is already awake anyway) to see if conditions are right to automatically 
 * check for updates. 
 * 
 * The second alarm is a backup (inexact) alarm that will actually wake up 
 * the device every few hours (if our interval alarm has not been fired 
 * because of no background activity). Because this only happens once every 
 * 3-6 hours and Android will attempt to schedule it together with other 
 * wakeups, effect on battery life should be completely insignificant. 
 *  
 * Last but not least, we're using an (exact) alarm that will fire if the
 * screen has been off for 5.5 hours. The idea is that you might be asleep
 * at this time and will wake up soon-ish, and we would not mind surprising
 * you with a fresh nightly.
 * 
 * The first two alarms only request a check for updates if the previous
 * check was 6 hours or longer ago. The last alarm will request that check
 * regardless. Regardless of those parameters, the update service will still
 * only perform the actual check if it's happy with the current network
 * (Wi-Fi) and battery (charging / juice aplenty) state. 
 */

package eu.chainfire.opendelta

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.SystemClock
import android.preference.PreferenceManager
import eu.chainfire.opendelta.ScreenState.OnScreenStateListener

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Scheduler(context: Context,
                onWantUpdateCheckListener: OnWantUpdateCheckListener) : OnScreenStateListener, OnSharedPreferenceChangeListener {

    private val onWantUpdateCheckListener: OnWantUpdateCheckListener? = null
    private var alarmManager: AlarmManager? = null
    private var prefs: SharedPreferences? = null

    private var alarmInterval: PendingIntent? = null
    private var alarmSecondaryWake: PendingIntent? = null
    private var alarmDetectSleep: PendingIntent? = null
    private var alarmCustom: PendingIntent? = null

    private var stopped: Boolean = false
    private var customAlarm: Boolean = false

    private val sdfLog = SimpleDateFormat("HH:mm",
            Locale.ENGLISH)

    interface OnWantUpdateCheckListener {
        fun onWantUpdateCheck(): Boolean
    }

    init {
        this.onWantUpdateCheckListener = onWantUpdateCheckListener
        alarmManager = context
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager
        prefs = PreferenceManager.getDefaultSharedPreferences(context)

        alarmInterval = UpdateService.alarmPending(context, 1)
        alarmSecondaryWake = UpdateService.alarmPending(context, 2)
        alarmDetectSleep = UpdateService.alarmPending(context, 3)
        alarmCustom = UpdateService.alarmPending(context, 4)

        stopped = true
    }

    private fun setSecondaryWakeAlarm() {
        Logger.d(
                "Setting secondary alarm (inexact) for %s",
                sdfLog.format(Date(System.currentTimeMillis() + ALARM_SECONDARY_WAKEUP_TIME)))
        alarmManager!!.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_SECONDARY_WAKEUP_TIME,
                alarmSecondaryWake)
    }

    private fun cancelSecondaryWakeAlarm() {
        Logger.d("Cancelling secondary alarm")
        alarmManager!!.cancel(alarmSecondaryWake)
    }

    private fun setDetectSleepAlarm() {
        Logger.i(
                "Setting sleep detection alarm (exact) for %s",
                sdfLog.format(Date(System.currentTimeMillis() + ALARM_DETECT_SLEEP_TIME)))
        alarmManager!!.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_DETECT_SLEEP_TIME,
                alarmDetectSleep)
    }

    private fun cancelDetectSleepAlarm() {
        Logger.d("Cancelling sleep detection alarm")
        alarmManager!!.cancel(alarmDetectSleep)
    }

    override fun onScreenState(state: Boolean) {
        if (!stopped && !customAlarm) {
            Logger.d("isScreenStateEnabled = " + isScreenStateEnabled(state))
            if (!state) {
                setDetectSleepAlarm()
            } else {
                cancelDetectSleepAlarm()
            }
        }
    }

    private fun isScreenStateEnabled(screenStateValue: Boolean): Boolean {
        val prefValue = prefs!!.getBoolean(
                SettingsActivity.PREF_SCREEN_STATE_OFF, true)
        return if (prefValue) {
            // only when screen off
            !screenStateValue
        } else true
    }

    private fun checkForUpdates(force: Boolean): Boolean {
        // Using abs here in case user changes date/time
        if (force || Math.abs(System.currentTimeMillis() - prefs!!.getLong(PREF_LAST_CHECK_ATTEMPT_TIME_NAME,
                        PREF_LAST_CHECK_ATTEMPT_TIME_DEFAULT)) > CHECK_THRESHOLD_MS) {
            if (onWantUpdateCheckListener != null) {
                if (onWantUpdateCheckListener.onWantUpdateCheck()) {
                    prefs!!.edit()
                            .putLong(PREF_LAST_CHECK_ATTEMPT_TIME_NAME,
                                    System.currentTimeMillis()).commit()
                }
            }
        } else {
            Logger.i("Skip checkForUpdates")
        }
        return false
    }

    fun alarm(id: Int) {
        when (id) {
            1 -> {
                // This is the interval alarm, called only if the device is
                // already awake for some reason. Might as well see if
                // conditions match to check for updates, right ?
                Logger.i("Interval alarm fired")
                checkForUpdates(false)
            }

            2 -> {
                // Fallback alarm. Our interval alarm has not been called for
                // several hours. The device might have been woken up just
                // for us. Let's see if conditions are good to check for
                // updates.
                Logger.i("Secondary alarm fired")
                checkForUpdates(false)
            }

            3 -> {
                // The screen has been off for 5:30 hours, with luck we've
                // caught the user asleep and we'll have a fresh build waiting
                // when (s)he wakes!
                Logger.i("Sleep detection alarm fired")
                checkForUpdates(true)
            }

            4 -> {
                // fixed daily alarm triggers
                Logger.i("Daily alarm fired")
                checkForUpdates(true)
            }
        }

        // Reset fallback wakeup command, we don't need to be called for another
        // few hours
        if (!customAlarm) {
            cancelSecondaryWakeAlarm()
            setSecondaryWakeAlarm()
        }
    }

    fun stop() {
        Logger.i("Stopping scheduler")
        cancelSecondaryWakeAlarm()
        cancelDetectSleepAlarm()
        alarmManager!!.cancel(alarmInterval)
        alarmManager!!.cancel(alarmCustom)
        stopped = true
    }

    fun start() {
        Logger.i("Starting scheduler")
        val alarmType = prefs!!.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART)
        customAlarm = alarmType == SettingsActivity.PREF_SCHEDULER_MODE_DAILY || alarmType == SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY

        if (customAlarm) {
            setCustomAlarmFromPrefs()
        } else {
            alarmManager!!.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_START,
                    ALARM_INTERVAL_INTERVAL, alarmInterval)

            setSecondaryWakeAlarm()
        }
        stopped = false
    }

    private fun setCustomAlarmFromPrefs() {
        if (customAlarm) {
            val dailyAlarmTime = prefs!!.getString(
                    SettingsActivity.PREF_SCHEDULER_DAILY_TIME, "00:00")
            val weeklyAlarmDay = prefs!!.getString(
                    SettingsActivity.PREF_SCHEDULER_WEEK_DAY, "1")
            val alarmType = prefs!!.getString(SettingsActivity.PREF_SCHEDULER_MODE, SettingsActivity.PREF_SCHEDULER_MODE_SMART)
            val dailyAlarm = alarmType == SettingsActivity.PREF_SCHEDULER_MODE_DAILY
            val weeklyAlarm = alarmType == SettingsActivity.PREF_SCHEDULER_MODE_WEEKLY

            if (dailyAlarm && dailyAlarmTime != null) {
                try {
                    val timeParts = dailyAlarmTime.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                    val hour = Integer.valueOf(timeParts[0])
                    val minute = Integer.valueOf(timeParts[1])
                    val c = Calendar.getInstance()
                    c.set(Calendar.HOUR_OF_DAY, hour)
                    c.set(Calendar.MINUTE, minute)

                    val format = SimpleDateFormat("kk:mm")
                    Logger.i("Setting daily alarm to %s", format.format(c.time))

                    alarmManager!!.cancel(alarmCustom)
                    alarmManager!!.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                            c.timeInMillis, AlarmManager.INTERVAL_DAY,
                            alarmCustom)
                } catch (e: Exception) {
                }

            }
            if (weeklyAlarm && dailyAlarmTime != null && weeklyAlarmDay != null) {
                try {
                    val timeParts = dailyAlarmTime.split(":".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
                    val hour = Integer.valueOf(timeParts[0])
                    val minute = Integer.valueOf(timeParts[1])
                    val c = Calendar.getInstance()
                    c.set(Calendar.DAY_OF_WEEK, Integer.valueOf(weeklyAlarmDay))
                    c.set(Calendar.HOUR_OF_DAY, hour)
                    c.set(Calendar.MINUTE, minute)
                    // next week
                    if (c.timeInMillis < Calendar.getInstance().timeInMillis) {
                        c.set(Calendar.WEEK_OF_YEAR, Calendar.getInstance().get(Calendar.WEEK_OF_YEAR) + 1)
                    }

                    val format = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' kk:mm")
                    Logger.i("Setting weekly alarm to %s", format.format(c.time))

                    alarmManager!!.cancel(alarmCustom)
                    alarmManager!!.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                            c.timeInMillis, AlarmManager.INTERVAL_DAY * 7,
                            alarmCustom)
                } catch (e: Exception) {
                }

            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences,
                                           key: String) {
        if (key == SettingsActivity.PREF_SCHEDULER_MODE) {
            if (!stopped) {
                stop()
                start()
            }
        }
        if (key == SettingsActivity.PREF_SCHEDULER_DAILY_TIME || key == SettingsActivity.PREF_SCHEDULER_WEEK_DAY) {
            setCustomAlarmFromPrefs()
        }
    }

    companion object {

        private val PREF_LAST_CHECK_ATTEMPT_TIME_NAME = "last_check_attempt_time"
        private val PREF_LAST_CHECK_ATTEMPT_TIME_DEFAULT = 0L

        private val CHECK_THRESHOLD_MS = 6 * AlarmManager.INTERVAL_HOUR
        private val ALARM_INTERVAL_START = AlarmManager.INTERVAL_FIFTEEN_MINUTES
        private val ALARM_INTERVAL_INTERVAL = AlarmManager.INTERVAL_HALF_HOUR
        private val ALARM_SECONDARY_WAKEUP_TIME = 3 * AlarmManager.INTERVAL_HOUR
        private val ALARM_DETECT_SLEEP_TIME = 5 * AlarmManager.INTERVAL_HOUR + AlarmManager.INTERVAL_HALF_HOUR
    }
}
