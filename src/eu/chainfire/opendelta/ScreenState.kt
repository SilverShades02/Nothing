/* 
 * Copyright (C) 2013-2014 Jorrit "Chainfire" Jongma
 * Copyright (C) 2013-2014 The OmniROM Project
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
import android.os.PowerManager

class ScreenState {

    private var context: Context? = null
    private var onScreenStateListener: OnScreenStateListener? = null
    @Volatile
    private var stateLast: Boolean? = null

    private var filter: IntentFilter? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateState(intent)
        }
    }

    val state: Boolean?
        get() = if (stateLast == null) false else stateLast!!.booleanValue()

    interface OnScreenStateListener {
        fun onScreenState(state: Boolean)
    }

    init {
        filter = IntentFilter()
        filter!!.addAction(Intent.ACTION_SCREEN_ON)
        filter!!.addAction(Intent.ACTION_SCREEN_OFF)
    }

    private fun updateState(intent: Intent?) {
        if (onScreenStateListener != null) {
            var state: Boolean? = null
            if (intent != null) {
                if (Intent.ACTION_SCREEN_ON == intent.action)
                    state = true
                if (Intent.ACTION_SCREEN_OFF == intent.action)
                    state = false
            }
            if (state == null) {
                state = (context!!.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isInteractive
            }

            if (stateLast == null || stateLast !== state) {
                stateLast = state
                onScreenStateListener!!.onScreenState(state)
            }
        }
    }

    fun start(context: Context, onScreenStateListener: OnScreenStateListener): Boolean {
        if (this.context == null) {
            this.context = context
            this.onScreenStateListener = onScreenStateListener
            context.registerReceiver(receiver, filter)
            updateState(null)
            return true
        }
        return false
    }

    fun stop(): Boolean {
        if (context != null) {
            context!!.unregisterReceiver(receiver)
            onScreenStateListener = null
            context = null
            return true
        }
        return false
    }
}
