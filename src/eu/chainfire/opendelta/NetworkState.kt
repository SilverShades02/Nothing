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
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.telephony.TelephonyManager

class NetworkState {

    private var context: Context? = null
    private var onNetworkStateListener: OnNetworkStateListener? = null
    private var connectivityManager: ConnectivityManager? = null
    @Volatile
    private var stateLast: Boolean? = null
    var isConnected: Boolean = false
        private set

    private var flags = ALLOW_WIFI or ALLOW_ETHERNET

    private val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateState()
        }
    }

    val state: Boolean?
        get() = if (stateLast == null) false else stateLast!!.booleanValue()

    interface OnNetworkStateListener {
        fun onNetworkState(state: Boolean)
    }

    private fun haveFlag(flag: Int): Boolean {
        return flags and flag == flag
    }

    private fun updateState() {
        if (onNetworkStateListener != null) {
            val info = connectivityManager!!.activeNetworkInfo

            var state = false
            isConnected = info != null && info.isConnected
            if (isConnected) {
                // My definitions of 2G/3G/4G may not match yours... :)
                // Speed estimates courtesy (c) 2013 the internets
                when (info!!.type) {
                    ConnectivityManager.TYPE_MOBILE -> when (info.subtype) {
                        TelephonyManager.NETWORK_TYPE_1xRTT,
                            // 2G ~ 50-100 kbps
                        TelephonyManager.NETWORK_TYPE_CDMA,
                            // 2G ~ 14-64 kbps
                        TelephonyManager.NETWORK_TYPE_EDGE,
                            // 2G ~ 50-100 kbps
                        TelephonyManager.NETWORK_TYPE_GPRS,
                            // 2G ~ 100 kbps *
                        TelephonyManager.NETWORK_TYPE_IDEN ->
                            // 2G ~ 25 kbps
                            state = haveFlag(ALLOW_2G)
                        TelephonyManager.NETWORK_TYPE_EHRPD,
                            // 3G ~ 1-2 Mbps
                        TelephonyManager.NETWORK_TYPE_EVDO_0,
                            // 3G ~ 400-1000 kbps
                        TelephonyManager.NETWORK_TYPE_EVDO_A,
                            // 3G ~ 600-1400 kbps
                        TelephonyManager.NETWORK_TYPE_EVDO_B,
                            // 3G ~ 5 Mbps
                        TelephonyManager.NETWORK_TYPE_HSDPA,
                            // 3G ~ 2-14 Mbps
                        TelephonyManager.NETWORK_TYPE_HSPA,
                            // 3G ~ 700-1700 kbps
                        TelephonyManager.NETWORK_TYPE_HSUPA,
                            // 3G ~ 1-23 Mbps *
                        TelephonyManager.NETWORK_TYPE_UMTS ->
                            // 3G ~ 400-7000 kbps
                            state = haveFlag(ALLOW_3G)
                        TelephonyManager.NETWORK_TYPE_HSPAP,
                            // 4G ~ 10-20 Mbps
                        TelephonyManager.NETWORK_TYPE_LTE ->
                            // 4G ~ 10+ Mbps
                            state = haveFlag(ALLOW_4G)
                        TelephonyManager.NETWORK_TYPE_UNKNOWN -> state = haveFlag(ALLOW_UNKNOWN)
                        else -> state = haveFlag(ALLOW_UNKNOWN)
                    }
                    ConnectivityManager.TYPE_WIFI -> state = haveFlag(ALLOW_WIFI)
                    ConnectivityManager.TYPE_ETHERNET -> state = haveFlag(ALLOW_ETHERNET)
                    ConnectivityManager.TYPE_WIMAX ->
                        // 4G
                        state = haveFlag(ALLOW_4G)
                    else -> state = haveFlag(ALLOW_UNKNOWN)
                }
            }

            if (stateLast == null || stateLast != state) {
                stateLast = state
                onNetworkStateListener!!.onNetworkState(state)
            }
        }
    }

    fun start(context: Context, onNetworkStateListener: OnNetworkStateListener, flags: Int): Boolean {
        if (this.context == null) {
            this.context = context
            this.onNetworkStateListener = onNetworkStateListener
            updateFlags(flags)
            connectivityManager = context
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            context.registerReceiver(receiver, filter)
            updateState()
            return true
        }
        return false
    }

    fun stop(): Boolean {
        if (context != null) {
            context!!.unregisterReceiver(receiver)
            onNetworkStateListener = null
            connectivityManager = null
            context = null
            return true
        }
        return false
    }

    fun updateFlags(newFlags: Int) {
        flags = newFlags
        Logger.d("networkstate flags --> %d", newFlags)
        if (connectivityManager != null)
            updateState()
    }

    companion object {

        val ALLOW_UNKNOWN = 1
        val ALLOW_2G = 2
        val ALLOW_3G = 4
        val ALLOW_4G = 8
        val ALLOW_WIFI = 16
        val ALLOW_ETHERNET = 32
    }
}
