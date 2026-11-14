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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.runtime.Composable
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
import org.uwuaosp.compose.widget.preference.PreferenceRow
import org.uwuaosp.compose.widget.preference.SettingsSection
import org.uwuaosp.compose.widget.preference.SwitchPreferenceRow
import org.uwuaosp.compose.widget.scaffold.SettingsScaffold
import org.uwuaosp.compose.widget.ui.SettingsHomepageIcon

class PrismSettingsActivity : ComponentActivity() {
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
        setContent {
            PrismTheme {
                PrismSettingsScreen(
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

}

@Composable
private fun PrismSettingsScreen(
    onNavigateUp: () -> Unit,
    onExportLogs: () -> Unit,
) {
    val context = LocalContext.current
    var gestureEnabled by remember {
        mutableStateOf(
            Settings.Secure.getInt(
                context.contentResolver,
                THREE_FINGER_SETTING,
                0,
            ) != 0,
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.app_name),
        showBackButton = true,
        onNavigateUp = onNavigateUp,
    ) {
        SettingsSection(title = stringResource(R.string.category_capture)) {
            item {
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
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        SettingsSection(title = stringResource(R.string.category_ocr_engine)) {
            item {
                PreferenceRow(
                    title = stringResource(R.string.ocr_engine_tesseract),
                    summary = stringResource(R.string.ocr_engine_tesseract_summary),
                    iconContent = {
                        SettingsHomepageIcon(imageVector = Icons.Filled.TextFields)
                    },
                    onClick = {},
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        SettingsSection(title = stringResource(R.string.category_diagnostics)) {
            item {
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
        }
    }

}

internal const val THREE_FINGER_SETTING = "uwu_prism_three_finger_gesture"
