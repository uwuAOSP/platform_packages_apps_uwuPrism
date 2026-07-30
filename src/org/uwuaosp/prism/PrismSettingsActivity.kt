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
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import org.uwuaosp.compose.settingslib.PreferenceGroupSpacer
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SettingsCategory
import org.uwuaosp.compose.settingslib.SettingsHomepageIcon
import org.uwuaosp.compose.settingslib.SettingsScaffold
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow

class PrismSettingsActivity : ComponentActivity() {
    private lateinit var ocrClient: OcrClient

    private val logExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri ?: return@registerForActivityResult
        thread(name = "uwuPrism-log-export") {
            val succeeded = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    BufferedWriter(OutputStreamWriter(output)).use { writer ->
                        val process = ProcessBuilder(
                            "logcat",
                            "-d",
                            "-v",
                            "threadtime",
                            "-s",
                            "uwuPrism",
                            "uwuOcrService",
                            "uwuOcrNative",
                        ).redirectErrorStream(true).start()
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach {
                                writer.appendLine(it)
                            }
                        }
                        process.waitFor()
                    }
                } ?: error("Cannot open destination")
            }.isSuccess
            if (!succeeded) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        R.string.log_export_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ocrClient = OcrClient(this)
        setContent {
            PrismTheme {
                PrismSettingsScreen(
                    ocrClient = ocrClient,
                    onNavigateUp = ::finish,
                    onExportLogs = {
                        val timestamp = SimpleDateFormat(
                            "yyyyMMdd-HHmmss",
                            Locale.US,
                        ).format(Date())
                        logExporter.launch("uwuPrism_$timestamp.log")
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ocrClient.connect()
    }

    override fun onStop() {
        ocrClient.disconnect()
        super.onStop()
    }
}

@Composable
private fun PrismSettingsScreen(
    ocrClient: OcrClient,
    onNavigateUp: () -> Unit,
    onExportLogs: () -> Unit,
) {
    val context = LocalContext.current
    val modelState by ocrClient.modelState.collectAsState()
    val preferences = remember {
        context.getSharedPreferences(PRISM_PREFERENCES, Context.MODE_PRIVATE)
    }
    var gestureEnabled by remember {
        mutableStateOf(
            Settings.Secure.getInt(
                context.contentResolver,
                THREE_FINGER_SETTING,
                0,
            ) != 0,
        )
    }
    var useVulkan by remember {
        mutableStateOf(preferences.getBoolean(PREF_USE_VULKAN, false))
    }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var promptedForMissingModel by remember { mutableStateOf(false) }

    LaunchedEffect(modelState.connected, modelState.status) {
        if (
            modelState.connected &&
            modelState.status == OcrModelStatus.MISSING &&
            !promptedForMissingModel
        ) {
            promptedForMissingModel = true
            showDownloadDialog = true
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.app_name),
        showBackButton = true,
        onNavigateUp = onNavigateUp,
    ) {
        SettingsCategory(title = stringResource(R.string.category_capture))
        SwitchPreferenceRow(
            title = stringResource(R.string.three_finger_gesture),
            summary = stringResource(R.string.three_finger_gesture_summary),
            checked = gestureEnabled,
            onCheckedChange = { enabled ->
                gestureEnabled = enabled
                Settings.Secure.putInt(
                    context.contentResolver,
                    THREE_FINGER_SETTING,
                    if (enabled) 1 else 0,
                )
            },
            iconContent = {
                SettingsHomepageIcon(imageVector = Icons.Filled.SwipeUp)
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsCategory(title = stringResource(R.string.category_model))
        PreferenceRow(
            title = stringResource(R.string.model_status),
            summary = modelStatusText(modelState),
            position = PreferencePosition.Top,
            iconContent = {
                SettingsHomepageIcon(imageVector = Icons.Filled.AutoAwesome)
            },
            onClick = {},
        )
        PreferenceGroupSpacer()
        PreferenceRow(
            title = when (modelState.status) {
                OcrModelStatus.DOWNLOADING, OcrModelStatus.VERIFYING ->
                    stringResource(R.string.cancel_download)
                OcrModelStatus.READY -> stringResource(R.string.delete_model)
                else -> stringResource(R.string.download_model)
            },
            summary = modelActionSummary(modelState),
            showSummary = modelState.totalBytes > 0,
            enabled = modelState.connected,
            position = PreferencePosition.Bottom,
            iconContent = {
                SettingsHomepageIcon(imageVector = Icons.Filled.Memory)
            },
            onClick = {
                when (modelState.status) {
                    OcrModelStatus.DOWNLOADING, OcrModelStatus.VERIFYING ->
                        ocrClient.cancelDownload()
                    OcrModelStatus.READY -> showDeleteDialog = true
                    else -> showDownloadDialog = true
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsCategory(title = stringResource(R.string.category_experimental))
        SwitchPreferenceRow(
            title = stringResource(R.string.vulkan_backend),
            summary = stringResource(R.string.vulkan_backend_summary),
            checked = useVulkan,
            onCheckedChange = { enabled ->
                useVulkan = enabled
                preferences.edit().putBoolean(PREF_USE_VULKAN, enabled).apply()
            },
            iconContent = {
                SettingsHomepageIcon(imageVector = Icons.Filled.AutoAwesome)
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsCategory(title = stringResource(R.string.category_diagnostics))
        PreferenceRow(
            title = stringResource(R.string.export_logs),
            summary = "",
            showSummary = false,
            iconContent = {
                SettingsHomepageIcon(imageVector = Icons.Filled.BugReport)
            },
            onClick = onExportLogs,
        )
    }

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.model_download_title)) },
            text = { Text(stringResource(R.string.model_download_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDownloadDialog = false
                        ocrClient.startDownload()
                    },
                ) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.model_delete_title)) },
            text = { Text(stringResource(R.string.model_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        ocrClient.deleteModels()
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun modelStatusText(state: OcrModelState): String {
    return when {
        !state.connected -> stringResource(R.string.service_unavailable)
        state.status == OcrModelStatus.MISSING -> stringResource(R.string.model_missing)
        state.status == OcrModelStatus.DOWNLOADING ->
            stringResource(R.string.model_downloading, state.progressPercent)
        state.status == OcrModelStatus.VERIFYING -> stringResource(R.string.model_verifying)
        state.status == OcrModelStatus.READY -> stringResource(R.string.model_ready)
        else -> state.error ?: stringResource(R.string.model_error)
    }
}

@Composable
private fun modelActionSummary(state: OcrModelState): String {
    if (state.totalBytes <= 0) return ""
    val megabyte = 1_000_000.0
    return stringResource(
        R.string.download_progress,
        state.downloadedBytes / megabyte,
        state.totalBytes / megabyte,
    )
}

internal const val PRISM_PREFERENCES = "uwu_prism_preferences"
internal const val PREF_USE_VULKAN = "use_vulkan"
