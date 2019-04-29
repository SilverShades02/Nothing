/*
 * Copyright (C) 2017 The OmniROM Project
 *
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

class Version(private val version: String?) : Comparable<Version> {

    fun get(): String {
        return this.version
    }

    init {
        if (version == null)
            throw IllegalArgumentException("Version can not be null")
        if (!version.matches("[0-9]+(\\.[0-9]+)*".toRegex()))
            throw IllegalArgumentException("Invalid version format")
    }

    override fun compareTo(that: Version): Int {
        if (that == null)
            return 1
        val thisParts = this.get().split("\\.".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
        val thatParts = that.get().split("\\.".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
        val length = Math.max(thisParts.size, thatParts.size)
        for (i in 0 until length) {
            val thisPart = if (i < thisParts.size)
                Integer.parseInt(thisParts[i])
            else
                0
            val thatPart = if (i < thatParts.size)
                Integer.parseInt(thatParts[i])
            else
                0
            if (thisPart < thatPart)
                return -1
            if (thisPart > thatPart)
                return 1
        }
        return 0
    }

    override fun equals(that: Any?): Boolean {
        if (this === that)
            return true
        if (that == null)
            return false
        return if (this.javaClass != that.javaClass) false else this.compareTo((that as Version?)!!) == 0
    }
}
