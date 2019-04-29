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

import java.util.Locale

object Logger {
    private val LOG_TAG = "OpenDelta"

    private var log = true

    fun setDebugLogging(enabled: Boolean) {
        log = enabled
    }

    fun d(message: String, vararg args: Any) {
        if (log)
            android.util.Log.d(LOG_TAG, String.format(Locale.ENGLISH, message, *args))
    }

    fun ex(e: Exception) {
        if (log)
            e.printStackTrace()
    }

    fun i(message: String, vararg args: Any) {
        android.util.Log.i(LOG_TAG, String.format(Locale.ENGLISH, message, *args))
    }
}
