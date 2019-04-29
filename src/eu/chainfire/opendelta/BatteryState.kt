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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.BatteryManager

class BatteryState : OnSharedPreferenceChangeListener {

    private var context: Context? = null
    private var onBatteryStateListener: OnBatteryStateListener? = null
    @Volatile
    private var stateLast: Boolean? = null

    private var minLevel = 50
    private var chargeOnly = true

    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            updateState(
                    level,
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            )
        }
    }

    val state: Boolean?
        get() = if (stateLast == null) false else stateLast!!.booleanValue()

    interface OnBatteryStateListener {
        fun onBatteryState(state: Boolean)
    }

    private fun updateState(level: Int, charging: Boolean) {
        if (onBatteryStateListener != null) {
            val state = charging && chargeOnly || level >= minLevel && !chargeOnly

            if (stateLast == null || stateLast != state) {
                stateLast = state
                onBatteryStateListener!!.onBatteryState(state)
            }
        }
    }

    fun start(context: Context, onBatteryStateListener: OnBatteryStateListener,
              minLevel: Int, chargeOnly: Boolean): Boolean {
        if (this.context == null) {
            this.context = context
            this.onBatteryStateListener = onBatteryStateListener
            this.minLevel = minLevel
            this.chargeOnly = chargeOnly
            context.registerReceiver(receiver, filter)
            return true
        }
        return false
    }

    fun stop(): Boolean {
        if (context != null) {
            context!!.unregisterReceiver(receiver)
            onBatteryStateListener = null
            context = null
            return true
        }
        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences,
                                           key: String) {
        chargeOnly = sharedPreferences.getBoolean(SettingsActivity.PREF_CHARGE_ONLY, true)
        minLevel = Integer.valueOf(sharedPreferences.getString(SettingsActivity.PREF_BATTERY_LEVEL, "50")).toInt()
    }
}