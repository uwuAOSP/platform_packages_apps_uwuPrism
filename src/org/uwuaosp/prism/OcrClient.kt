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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.uwuaosp.aicore.ocr.IOcrCallback
import org.uwuaosp.aicore.ocr.IOcrService

internal const val OCR_BIND_ACTION = "org.uwuaosp.aicore.action.BIND_OCR"
internal const val OCR_PERMISSION = "org.uwuaosp.aicore.permission.USE_OCR"
internal const val THREE_FINGER_SETTING = "uwu_prism_three_finger_gesture"

internal object OcrModelStatus {
    const val MISSING = 0
    const val DOWNLOADING = 1
    const val VERIFYING = 2
    const val READY = 3
    const val ERROR = 4
}

internal data class OcrModelState(
    val status: Int = OcrModelStatus.MISSING,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val connected: Boolean = false,
    val error: String? = null,
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0) {
            0
        } else {
            ((downloadedBytes.coerceAtMost(totalBytes) * 100) / totalBytes).toInt()
        }
}

internal class OcrClient(private val context: Context) {
    private val _modelState = MutableStateFlow(OcrModelState())
    val modelState: StateFlow<OcrModelState> = _modelState.asStateFlow()

    private val nextRequestId = AtomicLong(1)
    private val requests = ConcurrentHashMap<Long, CancellableContinuation<String>>()
    private var service: IOcrService? = null
    private var bound = false

    private val callback = object : IOcrCallback.Stub() {
        override fun onModelStateChanged(status: Int, downloadedBytes: Long, totalBytes: Long) {
            _modelState.value = _modelState.value.copy(
                status = status,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                error = null,
            )
        }

        override fun onRecognitionProgress(requestId: Long, progress: Int) = Unit

        override fun onRecognitionResult(requestId: Long, text: String) {
            requests.remove(requestId)?.resume(text)
        }

        override fun onError(requestId: Long, errorCode: Int, message: String) {
            if (requestId > 0) {
                requests.remove(requestId)?.resumeWithException(
                    OcrException(errorCode, message),
                )
            } else {
                _modelState.value = _modelState.value.copy(
                    status = OcrModelStatus.ERROR,
                    error = message,
                )
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = IOcrService.Stub.asInterface(binder)
            service = connectedService
            runCatching {
                connectedService.registerCallback(callback)
                _modelState.value = OcrModelState(
                    status = connectedService.modelState,
                    downloadedBytes = connectedService.downloadedBytes,
                    totalBytes = connectedService.downloadTotalBytes,
                    connected = true,
                )
            }.onFailure {
                Log.e(TAG, "Failed to initialize OCR service", it)
                service = null
                _modelState.value = _modelState.value.copy(connected = false)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            _modelState.value = _modelState.value.copy(connected = false)
            failPending(IllegalStateException("OCR service disconnected"))
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }
    }

    fun connect(
        prewarmOcr: Boolean = false,
        useVulkan: Boolean = false,
    ) {
        if (bound) return
        val intent = Intent(OCR_BIND_ACTION)
            .setPackage(AI_CORE_PACKAGE)
            .putExtra(EXTRA_PREWARM_OCR, prewarmOcr)
            .putExtra(EXTRA_USE_VULKAN, useVulkan)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            _modelState.value = _modelState.value.copy(connected = false)
        }
    }

    fun disconnect() {
        if (!bound) return
        runCatching { service?.unregisterCallback(callback) }
        context.unbindService(connection)
        bound = false
        service = null
        _modelState.value = _modelState.value.copy(connected = false)
        failPending(IllegalStateException("OCR client disconnected"))
    }

    fun startDownload() {
        service?.startDownload()
    }

    fun cancelDownload() {
        service?.cancelDownload()
    }

    fun deleteModels() {
        service?.deleteModels()
    }

    suspend fun recognize(imageFile: File, useVulkan: Boolean): String {
        val connectedService = service ?: throw IllegalStateException("OCR service unavailable")
        val requestId = nextRequestId.getAndIncrement()
        return suspendCancellableCoroutine { continuation ->
            requests[requestId] = continuation
            continuation.invokeOnCancellation {
                requests.remove(requestId)
                runCatching { connectedService.cancelRecognition(requestId) }
            }
            try {
                ParcelFileDescriptor.open(
                    imageFile,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { descriptor ->
                    connectedService.recognize(descriptor, requestId, useVulkan)
                }
            } catch (error: Throwable) {
                val pending = requests.remove(requestId)
                runCatching { connectedService.cancelRecognition(requestId) }
                pending?.resumeWithException(error)
            }
        }
    }

    private fun failPending(error: Throwable) {
        requests.entries.forEach { (requestId, continuation) ->
            if (requests.remove(requestId, continuation)) {
                continuation.resumeWithException(error)
            }
        }
    }

    private companion object {
        const val TAG = "uwuPrism"
        const val AI_CORE_PACKAGE = "org.uwuaosp.aicore"
        const val EXTRA_PREWARM_OCR = "org.uwuaosp.prism.extra.PREWARM_OCR"
        const val EXTRA_USE_VULKAN = "org.uwuaosp.prism.extra.USE_VULKAN"
    }
}

internal class OcrException(val code: Int, message: String) : Exception(message)
