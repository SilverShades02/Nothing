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

import org.json.JSONException
import org.json.JSONObject

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

class DeltaInfo @Throws(JSONException::class, NullPointerException::class)
constructor(raw: ByteArray, val isRevoked: Boolean) {

    val version: Int
    val `in`: FileFull
    val update: FileUpdate
    val signature: FileUpdate
    val out: FileFull

    interface ProgressListener {
        fun onProgress(progress: Float, current: Long, total: Long)
        fun setStatus(status: String)
    }

    inner class FileSizeMD5 @Throws(JSONException::class)
    constructor(`object`: JSONObject, suffix: String?) {
        val size: Long
        val mD5: String

        init {
            size = `object`.getLong("size" + if (suffix != null) "_$suffix" else "")
            mD5 = `object`.getString("md5" + if (suffix != null) "_$suffix" else "")
        }
    }

    open inner class FileBase @Throws(JSONException::class)
    constructor(`object`: JSONObject) {
        val name: String
        var tag: Any? = null

        init {
            name = `object`.getString("name")
        }

        open fun match(f: File, checkMD5: Boolean, progressListener: ProgressListener): FileSizeMD5? {
            return null
        }
    }

    inner class FileUpdate @Throws(JSONException::class)
    constructor(`object`: JSONObject) : FileBase(`object`) {
        val update: FileSizeMD5
        val applied: FileSizeMD5

        init {
            update = FileSizeMD5(`object`, null)
            applied = FileSizeMD5(`object`, "applied")
        }

        override fun match(f: File, checkMD5: Boolean, progressListener: ProgressListener): FileSizeMD5? {
            if (f.exists()) {
                if (f.length() == update.size)
                    if (!checkMD5 || update.mD5 == getFileMD5(f, progressListener))
                        return update
                if (f.length() == applied.size)
                    if (!checkMD5 || applied.mD5 == getFileMD5(f, progressListener))
                        return applied
            }
            return null
        }
    }

    inner class FileFull @Throws(JSONException::class)
    constructor(`object`: JSONObject) : FileBase(`object`) {
        val official: FileSizeMD5
        val store: FileSizeMD5
        val storeSigned: FileSizeMD5

        init {
            official = FileSizeMD5(`object`, "official")
            store = FileSizeMD5(`object`, "store")
            storeSigned = FileSizeMD5(`object`, "store_signed")
        }

        override fun match(f: File, checkMD5: Boolean, progressListener: ProgressListener): FileSizeMD5? {
            if (f.exists()) {
                if (f.length() == official.size)
                    if (!checkMD5 || official.mD5 == getFileMD5(f, progressListener))
                        return official
                if (f.length() == store.size)
                    if (!checkMD5 || store.mD5 == getFileMD5(f, progressListener))
                        return store
                if (f.length() == storeSigned.size)
                    if (!checkMD5 || storeSigned.mD5 == getFileMD5(f, progressListener))
                        return storeSigned
            }
            return null
        }

        fun isOfficialFile(f: File): Boolean {
            return if (f.exists()) {
                f.length() == official.size
            } else false
        }

        fun isSignedFile(f: File): Boolean {
            return if (f.exists()) {
                f.length() == storeSigned.size
            } else false
        }
    }

    init {
        var `object`: JSONObject? = null
        try {
            `object` = JSONObject(String(raw, "UTF-8"))
        } catch (e: UnsupportedEncodingException) {
            // Doesn't happen, UTF-8 is guaranteed to be available on Android
        }

        version = `object`!!.getInt("version")
        `in` = FileFull(`object`.getJSONObject("in"))
        update = FileUpdate(`object`.getJSONObject("update"))
        signature = FileUpdate(`object`.getJSONObject("signature"))
        out = FileFull(`object`.getJSONObject("out"))
    }

    private fun getProgress(current: Long, total: Long): Float {
        return if (total == 0L) 0f else current.toFloat() / total.toFloat() * 100f
    }

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
}
