/*
 * Copyright (C) 2026 The uwuAOSP Project
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

package org.uwuaosp.prism

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class OcrEngine(val preferenceValue: String) {
    LocalModel("local_model"),
    Tesseract("tesseract"),
    ;

    companion object {
        fun fromPreference(value: String?): OcrEngine {
            return entries.firstOrNull { it.preferenceValue == value } ?: LocalModel
        }
    }
}

internal fun selectedOcrEngine(context: Context): OcrEngine {
    return OcrEngine.fromPreference(
        context.getSharedPreferences(PRISM_PREFERENCES, Context.MODE_PRIVATE)
            .getString(PREF_OCR_ENGINE, OcrEngine.Tesseract.preferenceValue),
    )
}

internal object TesseractOcr {
    private const val TESSDATA_DIRECTORY = "tessdata"
    private val languageFiles = listOf("chi_sim.traineddata", "eng.traineddata")

    init {
        System.loadLibrary("uwu_prism_tesseract_jni")
    }

    suspend fun recognize(context: Context, image: File): String {
        return withContext(Dispatchers.IO) {
            nativeRecognize(image.absolutePath, ensureTessdata(context).absolutePath)
        }
    }

    @Synchronized
    private fun ensureTessdata(context: Context): File {
        val directory = File(context.filesDir, TESSDATA_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) { "Cannot create Tesseract data directory" }
        languageFiles.forEach { fileName ->
            val destination = File(directory, fileName)
            val assetName = "$TESSDATA_DIRECTORY/$fileName"
            val expectedSize = context.assets.openFd(assetName).use { it.length }
            if (destination.isFile && destination.length() == expectedSize) return@forEach

            val temporary = File(directory, "$fileName.new")
            context.assets.open(assetName).use { input ->
                FileOutputStream(temporary).use(input::copyTo)
            }
            check(temporary.renameTo(destination)) { "Cannot install Tesseract language data" }
        }
        return directory
    }

    private external fun nativeRecognize(imagePath: String, tessdataPath: String): String
}
