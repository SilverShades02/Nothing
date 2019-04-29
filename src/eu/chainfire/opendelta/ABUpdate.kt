/*
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.chainfire.opendelta

import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.util.Log

import java.util.Enumeration
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.ArrayList

import eu.chainfire.opendelta.DeltaInfo.ProgressListener

internal class ABUpdate private constructor(private val zipPath: String, private val mProgressListener: ProgressListener?,
                                            private val updateservice: UpdateService) {
    private val enableABPerfMode: Boolean

    private val mUpdateEngineCallback = object : UpdateEngineCallback() {
        internal var lastPercent: Float = 0.toFloat()
        internal var offset = 0
        fun onStatusUpdate(status: Int, percent: Float) {
            if (status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT) {
                updateservice.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.SUCCESS)
                return
            }
            if (status == UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT) {
                updateservice.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.ERROR)
                return
            }
            if (lastPercent > percent) {
                offset = 50
            }
            lastPercent = percent
            if (mProgressListener != null) {
                mProgressListener.setStatus(updateservice.getString(updateservice.resources.getIdentifier(
                        "progress_status_$status", "string", updateservice.packageName)))
                mProgressListener.onProgress(percent * 50f + offset.toFloat(),
                        Math.round(percent * 50).toLong() + offset.toLong(), 100L)
            }
        }

        fun onPayloadApplicationComplete(errorCode: Int) {
            mProgressListener!!.onProgress(100f, 100L, 100L)
            updateservice.onUpdateCompleted(errorCode)
            setInstallingUpdate(false, updateservice)
        }
    }

    init {
        this.enableABPerfMode = updateservice.config!!.abPerfModeCurrent
    }

    private fun startUpdate(): Boolean {
        val file = File(zipPath)
        if (!file.exists()) {
            Log.e(TAG, "The given update doesn't exist")
            return false
        }

        val offset: Long
        var headerKeyValuePairs: Array<String>
        try {
            val zipFile = ZipFile(file)
            offset = getZipEntryOffset(zipFile, PAYLOAD_BIN_PATH)
            val payloadPropEntry = zipFile.getEntry(PAYLOAD_PROPERTIES_PATH)
            zipFile.getInputStream(payloadPropEntry).use { `is` ->
                InputStreamReader(`is`).use { isr ->
                    BufferedReader(isr).use { br ->
                        val lines = ArrayList<String>()
                        var line: String
                        while ((line = br.readLine()) != null) {
                            lines.add(line)
                        }
                        headerKeyValuePairs = arrayOfNulls(lines.size)
                        headerKeyValuePairs = lines.toTypedArray<String>()
                    }
                }
            }
            zipFile.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not prepare $file", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Could not prepare $file", e)
            return false
        }

        val updateEngine = UpdateEngine()
        updateEngine.setPerformanceMode(enableABPerfMode)
        updateEngine.bind(mUpdateEngineCallback)
        val zipFileUri = "file://" + file.absolutePath
        updateEngine.applyPayload(zipFileUri, offset, 0, headerKeyValuePairs)

        return true
    }

    companion object {

        private val TAG = "ABUpdateInstaller"

        private val PAYLOAD_BIN_PATH = "payload.bin"
        private val PAYLOAD_PROPERTIES_PATH = "payload_properties.txt"

        private val PREFS_IS_INSTALLING_UPDATE = "prefs_is_installing_update"

        @Synchronized
        fun start(zipPath: String, listener: ProgressListener,
                  us: UpdateService): Boolean {
            if (isInstallingUpdate(us)) {
                return false
            }
            val installer = ABUpdate(zipPath, listener, us)
            setInstallingUpdate(installer.startUpdate(), us)
            return isInstallingUpdate(us)
        }

        @Synchronized
        fun isInstallingUpdate(us: UpdateService): Boolean {
            return us.prefs!!
                    .getBoolean(PREFS_IS_INSTALLING_UPDATE, false)
        }

        @Synchronized
        fun setInstallingUpdate(installing: Boolean, us: UpdateService) {
            us.prefs!!.edit()
                    .putBoolean(PREFS_IS_INSTALLING_UPDATE, installing).commit()
        }

        fun isABUpdate(zipFile: ZipFile): Boolean {
            return zipFile.getEntry(PAYLOAD_BIN_PATH) != null && zipFile.getEntry(PAYLOAD_PROPERTIES_PATH) != null
        }

        /**
         * Get the offset to the compressed data of a file inside the given zip
         *
         * @param zipFile input zip file
         * @param entryPath full path of the entry
         * @return the offset of the compressed, or -1 if not found
         * @throws IOException
         * @throws IllegalArgumentException if the given entry is not found
         */
        @Throws(IOException::class)
        fun getZipEntryOffset(zipFile: ZipFile, entryPath: String): Long {
            // Each entry has an header of (30 + n + m) bytes
            // 'n' is the length of the file name
            // 'm' is the length of the extra field
            val FIXED_HEADER_SIZE = 30
            val zipEntries = zipFile.entries()
            var offset: Long = 0
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                val n = entry.name.length
                val m = if (entry.extra == null) 0 else entry.extra.size
                val headerSize = FIXED_HEADER_SIZE + n + m
                offset += headerSize.toLong()
                if (entry.name == entryPath) {
                    return offset
                }
                offset += entry.compressedSize
            }
            Log.e(TAG, "Entry $entryPath not found")
            throw IllegalArgumentException("The given entry was not found")
        }
    }
}
