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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrismCaptureActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ocrClient: OcrClient
    private lateinit var controller: CaptureController
    private lateinit var ocrEngine: OcrEngine
    private var screenshotUri: Uri? = null

    private val pdfExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        uri?.let { controller.exportDocument(ExportType.Pdf, it) }
    }

    private val textExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let { controller.exportDocument(ExportType.Text, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        screenshotUri = intent.data ?: intent.parcelableUriExtra(Intent.EXTRA_STREAM)
        val uri = screenshotUri
        if (uri == null || uri.scheme != "content") {
            Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        ocrEngine = selectedOcrEngine(this)
        ocrClient = OcrClient(this)
        controller = CaptureController(this, ocrClient, activityScope, ocrEngine)
        if (ocrEngine == OcrEngine.LocalModel) {
            ocrClient.connect(
                prewarmOcr = true,
                useVulkan = getSharedPreferences(
                    PRISM_PREFERENCES,
                    Context.MODE_PRIVATE,
                ).getBoolean(PREF_USE_VULKAN, false),
            )
        }

        setContent {
            PrismTheme {
                val bitmap by controller.bitmap.collectAsState()
                if (bitmap == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    CaptureScreen(
                        controller = controller,
                        modelState = ocrClient.modelState.collectAsState().value,
                        ocrEngine = ocrEngine,
                        onClose = ::finish,
                        onExportPdf = {
                            pdfExporter.launch(getString(R.string.pdf_file_name))
                        },
                        onExportText = {
                            textExporter.launch(getString(R.string.txt_file_name))
                        },
                    )
                }
            }
        }

        activityScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }.getOrNull()
            }
            if (loaded == null) {
                Toast.makeText(
                    this@PrismCaptureActivity,
                    R.string.capture_failed,
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            } else {
                controller.setBitmap(loaded)
            }
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.cancel()
        }
        if (::ocrClient.isInitialized) {
            ocrClient.disconnect()
        }
        activityScope.cancel()
        if (!isChangingConfigurations) {
            clearTemporaryCrops(this)
            screenshotUri?.let { uri ->
                runCatching { contentResolver.delete(uri, null, null) }
                revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        super.onDestroy()
    }

    private fun Intent.parcelableUriExtra(name: String): Uri? {
        return getParcelableExtra(name, Uri::class.java)
    }
}

private enum class ExportType {
    Pdf,
    Text,
}

private class CaptureController(
    private val context: Context,
    private val ocrClient: OcrClient,
    private val scope: CoroutineScope,
    private val ocrEngine: OcrEngine,
) {
    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()
    val selections = mutableStateListOf<SelectionRegion>()

    var selectionKind by mutableStateOf(RegionKind.Text)
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var showModelDownloadDialog by mutableStateOf(false)
    var exportMenuExpanded by mutableStateOf(false)
    private var operation: Job? = null

    fun setBitmap(bitmap: Bitmap) {
        _bitmap.value = bitmap
    }

    fun copyAll() {
        val source = _bitmap.value ?: return
        if (!validateSelections()) return
        val textSelections = selections.filter { it.kind == RegionKind.Text }
        if (textSelections.isNotEmpty() && !ensureModelReady()) return
        runOperation {
            val processed = recognizeSelections(source, textSelections)
            try {
                val text = processed
                    .sortedBy { it.selection.id }
                    .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
                    .joinToString("\n\n")
                if (text.isNotEmpty()) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("uwuPrism-OCR", text))
                }
                val imageSelections = selections.filter { it.kind == RegionKind.Image }
                val saved = if (imageSelections.isEmpty()) {
                    0
                } else {
                    saveRegionsToPictures(context, source, imageSelections)
                }
                val message = when {
                    text.isNotEmpty() && saved > 0 ->
                        context.getString(R.string.copy_complete, saved)
                    text.isNotEmpty() -> context.getString(R.string.copy_text_complete)
                    else -> context.getString(R.string.save_images_complete, saved)
                }
                toast(message)
            } finally {
                processed.forEach { it.bitmap.recycle() }
            }
        }
    }

    fun exportImages() {
        val source = _bitmap.value ?: return
        if (!validateSelections()) return
        runOperation {
            val saved = saveRegionsToPictures(context, source, selections.toList())
            toast(context.getString(R.string.save_images_complete, saved))
        }
    }

    fun exportDocument(type: ExportType, uri: Uri) {
        val source = _bitmap.value ?: return
        if (!validateSelections()) return
        val needsOcr = selections.any { it.kind == RegionKind.Text }
        if (needsOcr && !ensureModelReady()) return
        runOperation {
            val sourceSelections = when (type) {
                ExportType.Pdf -> selections.toList()
                ExportType.Text -> selections.filter { it.kind == RegionKind.Text }
            }
            val processed = recognizeSelections(source, sourceSelections)
            try {
                when (type) {
                    ExportType.Pdf -> writePdfExport(context, uri, processed)
                    ExportType.Text -> writeTextExport(context, uri, processed)
                }
                toast(context.getString(R.string.export_complete))
            } finally {
                processed.forEach { it.bitmap.recycle() }
            }
        }
    }

    fun startModelDownload() {
        showModelDownloadDialog = false
        ocrClient.startDownload()
    }

    fun cancel() {
        val source = _bitmap.value
        _bitmap.value = null
        val activeOperation = operation
        if (activeOperation?.isActive == true) {
            activeOperation.invokeOnCompletion { source?.recycle() }
            activeOperation.cancel()
        } else {
            source?.recycle()
        }
    }

    private fun validateSelections(): Boolean {
        if (selections.isNotEmpty()) return true
        toast(context.getString(R.string.nothing_selected))
        return false
    }

    private fun ensureModelReady(): Boolean {
        if (ocrEngine == OcrEngine.Tesseract) return true
        val state = ocrClient.modelState.value
        if (state.status == OcrModelStatus.READY) return true
        showModelDownloadDialog = true
        return false
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = scope.launch {
            busyMessage = context.getString(R.string.working)
            runCatching { block() }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    toast(
                        context.getString(
                            R.string.export_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            busyMessage = null
        }
    }

    private suspend fun recognizeSelections(
        source: Bitmap,
        sourceSelections: List<SelectionRegion>,
    ): List<ProcessedRegion> {
        val textCount = sourceSelections.count { it.kind == RegionKind.Text }
        var textIndex = 0
        val processed = mutableListOf<ProcessedRegion>()
        try {
            sourceSelections.sortedBy { it.id }.forEach { selection ->
                val crop = cropBitmap(source, selection)
                try {
                    if (selection.kind == RegionKind.Text) {
                        textIndex += 1
                        busyMessage = context.getString(
                            R.string.recognizing_region,
                            textIndex,
                            textCount,
                        )
                        val file = writeTemporaryCrop(context, crop, selection.id)
                        val text = try {
                            when (ocrEngine) {
                                OcrEngine.LocalModel -> {
                                    val useVulkan = context.getSharedPreferences(
                                        PRISM_PREFERENCES,
                                        Context.MODE_PRIVATE,
                                    ).getBoolean(PREF_USE_VULKAN, false)
                                    ocrClient.recognize(file, useVulkan)
                                }
                                OcrEngine.Tesseract -> TesseractOcr.recognize(context, file)
                            }
                        } finally {
                            file.delete()
                        }
                        processed += ProcessedRegion(selection, crop, text)
                    } else {
                        processed += ProcessedRegion(selection, crop)
                    }
                } catch (error: Throwable) {
                    crop.recycle()
                    throw error
                }
            }
            return processed
        } catch (error: Throwable) {
            processed.forEach { it.bitmap.recycle() }
            throw error
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun CaptureScreen(
    controller: CaptureController,
    modelState: OcrModelState,
    ocrEngine: OcrEngine,
    onClose: () -> Unit,
    onExportPdf: () -> Unit,
    onExportText: () -> Unit,
) {
    val bitmap = controller.bitmap.collectAsState().value ?: return
    var promptedForMissingModel by remember { mutableStateOf(false) }

    LaunchedEffect(ocrEngine, modelState.connected, modelState.status) {
        if (
            ocrEngine == OcrEngine.LocalModel &&
            modelState.connected &&
            modelState.status == OcrModelStatus.MISSING &&
            !promptedForMissingModel
        ) {
            promptedForMissingModel = true
            controller.showModelDownloadDialog = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val imageAspectRatio = bitmap.width.toFloat() / bitmap.height
        val viewportAspectRatio = constraints.maxWidth.toFloat() / constraints.maxHeight
        val imageModifier = if (imageAspectRatio > viewportAspectRatio) {
            Modifier.fillMaxWidth().aspectRatio(imageAspectRatio)
        } else {
            Modifier.fillMaxHeight().aspectRatio(
                imageAspectRatio,
                matchHeightConstraintsFirst = true,
            )
        }
        Box(modifier = imageModifier.align(Alignment.Center)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            SelectionEditor(
                selections = controller.selections,
                currentKind = controller.selectionKind,
            )
        }
        CaptureTopControls(
            currentKind = controller.selectionKind,
            modelState = modelState,
            onKindChanged = { controller.selectionKind = it },
            onClose = onClose,
        )
        CaptureBottomControls(
            expanded = controller.exportMenuExpanded,
            onExpandedChange = { controller.exportMenuExpanded = it },
            onCopy = controller::copyAll,
            onExportPdf = {
                controller.exportMenuExpanded = false
                onExportPdf()
            },
            onExportText = {
                controller.exportMenuExpanded = false
                onExportText()
            },
            onExportImages = {
                controller.exportMenuExpanded = false
                controller.exportImages()
            },
        )
        controller.busyMessage?.let { message ->
            BusyOverlay(message)
        }
    }

    if (controller.showModelDownloadDialog) {
        AlertDialog(
            onDismissRequest = { controller.showModelDownloadDialog = false },
            title = { Text(stringResource(R.string.model_download_title)) },
            text = { Text(stringResource(R.string.model_download_message)) },
            confirmButton = {
                TextButton(onClick = controller::startModelDownload) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { controller.showModelDownloadDialog = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BoxScope.CaptureTopControls(
    currentKind: RegionKind,
    modelState: OcrModelState,
    onKindChanged: (RegionKind) -> Unit,
    onClose: () -> Unit,
) {
    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 16.dp, top = statusPadding + 12.dp)
            .size(48.dp)
            .clickable(onClick = onClose),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.close),
            )
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = statusPadding + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactKindSelector(
            currentKind = currentKind,
            onKindChanged = onKindChanged,
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ) {
            Text(
                text = stringResource(R.string.selection_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        if (
            modelState.status == OcrModelStatus.DOWNLOADING ||
            modelState.status == OcrModelStatus.VERIFYING
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
                Column(
                    modifier = Modifier.width(180.dp).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (modelState.status == OcrModelStatus.VERIFYING) {
                            stringResource(R.string.model_verifying)
                        } else {
                            stringResource(
                                R.string.model_downloading,
                                modelState.progressPercent,
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = { modelState.progressPercent / 100f },
                        modifier = Modifier.width(160.dp),
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun CompactKindSelector(
    currentKind: RegionKind,
    onKindChanged: (RegionKind) -> Unit,
) {
    val colors = ToggleButtonDefaults.toggleButtonColors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(
            ButtonGroupDefaults.ConnectedSpaceBetween,
        ),
    ) {
        KindSegment(
            selected = currentKind == RegionKind.Text,
            leading = true,
            colors = colors,
            label = stringResource(R.string.selection_text),
            icon = {
                Icon(
                    Icons.Filled.TextFields,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { onKindChanged(RegionKind.Text) },
        )
        KindSegment(
            selected = currentKind == RegionKind.Image,
            leading = false,
            colors = colors,
            label = stringResource(R.string.selection_image),
            icon = {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { onKindChanged(RegionKind.Image) },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun KindSegment(
    selected: Boolean,
    leading: Boolean,
    colors: androidx.compose.material3.ToggleButtonColors,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val shapes = if (leading) {
        ButtonGroupDefaults.connectedLeadingButtonShapes()
    } else {
        ButtonGroupDefaults.connectedTrailingButtonShapes()
    }
    ToggleButton(
        checked = selected,
        onCheckedChange = { checked ->
            if (checked) onClick()
        },
        shapes = shapes,
        colors = colors,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .width(104.dp),
    ) {
        icon()
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun BoxScope.CaptureBottomControls(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onExportPdf: () -> Unit,
    onExportText: () -> Unit,
    onExportImages: () -> Unit,
) {
    val navigationPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val moreExportsDescription = stringResource(R.string.more_exports)
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = navigationPadding + 18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Surface(
                modifier = Modifier
                    .height(60.dp)
                    .width(154.dp)
                    .clickable(onClick = onCopy),
                shape = RoundedCornerShape(
                    topStart = 30.dp,
                    bottomStart = 30.dp,
                    topEnd = 5.dp,
                    bottomEnd = 5.dp,
                ),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 5.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.copy),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Box {
                Surface(
                    modifier = Modifier
                        .height(60.dp)
                        .width(62.dp)
                        .clickable { onExpandedChange(true) }
                        .semantics {
                            contentDescription = moreExportsDescription
                        },
                    shape = RoundedCornerShape(
                        topStart = 5.dp,
                        bottomStart = 5.dp,
                        topEnd = 30.dp,
                        bottomEnd = 30.dp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 5.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.more_exports),
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) },
                    modifier = Modifier.widthIn(min = 180.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    shadowElevation = 3.dp,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_pdf)) },
                        onClick = onExportPdf,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_txt)) },
                        onClick = onExportText,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_images)) },
                        onClick = onExportImages,
                    )
                }
            }
        }
    }
}

@Composable
private fun BusyOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .pointerInput(message) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    var pointerPressed = true
                    while (pointerPressed) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        pointerPressed = event.changes.any { it.pressed }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(text = message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private sealed interface DragAction {
    val pointerId: PointerId

    data class Create(
        override val pointerId: PointerId,
        val regionId: Long,
        val start: Offset,
    ) : DragAction

    data class Move(
        override val pointerId: PointerId,
        val regionId: Long,
        val start: Offset,
        val original: SelectionRegion,
    ) : DragAction

    data class Resize(
        override val pointerId: PointerId,
        val regionId: Long,
        val corner: Corner,
        val original: SelectionRegion,
    ) : DragAction
}

private enum class Corner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

@Composable
private fun SelectionEditor(
    selections: MutableList<SelectionRegion>,
    currentKind: RegionKind,
) {
    val textLabel = stringResource(R.string.selection_text)
    val imageLabel = stringResource(R.string.selection_image)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val density = LocalDensity.current
    var nextId by remember { mutableLongStateOf(1) }
    val handleRadius = with(density) { 6.dp.toPx() }
    val hitRadius = with(density) { 26.dp.toPx() }
    val deleteRadius = with(density) { 13.dp.toPx() }
    val minRegionSize = with(density) { 44.dp.toPx() }
    val strokeWidth = with(density) { 2.dp.toPx() }
    val labelTextSize = with(density) { 12.dp.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(currentKind) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val canvasSize = size
                    val deleteTarget = selections.asReversed().firstOrNull { region ->
                        val rect = region.toPixelRect(canvasSize)
                        down.position.distanceTo(Offset(rect.right, rect.top)) <= hitRadius
                    }
                    if (deleteTarget != null) {
                        selections.removeAll { it.id == deleteTarget.id }
                        down.consume()
                        return@awaitEachGesture
                    }

                    val resizeTarget = selections.asReversed().firstNotNullOfOrNull { region ->
                        val rect = region.toPixelRect(canvasSize)
                        Corner.entries.firstOrNull { corner ->
                            down.position.distanceTo(rect.corner(corner)) <= hitRadius
                        }?.let { region to it }
                    }
                    val moveTarget = if (resizeTarget == null) {
                        selections.asReversed().firstOrNull {
                            it.toPixelRect(canvasSize).contains(
                                down.position.x,
                                down.position.y,
                            )
                        }
                    } else {
                        null
                    }

                    val action: DragAction = when {
                        resizeTarget != null -> DragAction.Resize(
                            pointerId = down.id,
                            regionId = resizeTarget.first.id,
                            corner = resizeTarget.second,
                            original = resizeTarget.first,
                        )
                        moveTarget != null -> DragAction.Move(
                            pointerId = down.id,
                            regionId = moveTarget.id,
                            start = down.position,
                            original = moveTarget,
                        )
                        else -> {
                            val id = nextId++
                            val normalized = down.position.normalized(canvasSize)
                            selections.add(
                                SelectionRegion(
                                    id = id,
                                    kind = currentKind,
                                    left = normalized.x,
                                    top = normalized.y,
                                    right = normalized.x,
                                    bottom = normalized.y,
                                ),
                            )
                            DragAction.Create(down.id, id, down.position)
                        }
                    }
                    down.consume()

                    var lastPosition = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == action.pointerId }
                            ?: break
                        lastPosition = change.position
                        updateRegionForDrag(
                            selections = selections,
                            action = action,
                            position = change.position,
                            canvasSize = canvasSize,
                        )
                        change.consume()
                        if (!change.pressed) break
                    }

                    val finalRegion = selections.firstOrNull {
                        it.id == when (action) {
                            is DragAction.Create -> action.regionId
                            is DragAction.Move -> action.regionId
                            is DragAction.Resize -> action.regionId
                        }
                    }
                    if (finalRegion != null) {
                        val finalRect = finalRegion.toPixelRect(canvasSize)
                        if (
                            finalRect.width() < minRegionSize ||
                            finalRect.height() < minRegionSize ||
                            lastPosition.x.isNaN()
                        ) {
                            selections.removeAll { it.id == finalRegion.id }
                        }
                    }
                }
            },
    ) {
        drawContext.canvas.nativeCanvas.run {
            save()
            selections.forEach { region ->
                val rect = region.toPixelRect(size)
                clipOutRect(rect)
            }
            drawColor(android.graphics.Color.argb(112, 20, 20, 22))
            restore()
        }

        selections.sortedBy { it.id }.forEachIndexed { index, region ->
            val rect = region.toPixelRect(size)
            val color = if (region.kind == RegionKind.Text) primary else tertiary
            drawRect(
                color = color,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width(), rect.height()),
                style = Stroke(width = strokeWidth),
            )
            Corner.entries.forEach { corner ->
                drawCircle(
                    color = color,
                    radius = handleRadius,
                    center = rect.corner(corner),
                )
            }
            drawCircle(
                color = color,
                radius = deleteRadius,
                center = Offset(rect.right, rect.top),
            )
            val cross = deleteRadius * 0.42f
            drawLine(
                color = onPrimary,
                start = Offset(rect.right - cross, rect.top - cross),
                end = Offset(rect.right + cross, rect.top + cross),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color = onPrimary,
                start = Offset(rect.right + cross, rect.top - cross),
                end = Offset(rect.right - cross, rect.top + cross),
                strokeWidth = strokeWidth,
            )

            val label = "${if (region.kind == RegionKind.Text) textLabel else imageLabel} ${index + 1}"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                textSize = labelTextSize
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawContext.canvas.nativeCanvas.drawText(
                label,
                rect.left + strokeWidth * 2,
                max(labelTextSize, rect.top - strokeWidth * 3),
                paint,
            )
        }
    }
}

private fun updateRegionForDrag(
    selections: MutableList<SelectionRegion>,
    action: DragAction,
    position: Offset,
    canvasSize: androidx.compose.ui.unit.IntSize,
) {
    val id = when (action) {
        is DragAction.Create -> action.regionId
        is DragAction.Move -> action.regionId
        is DragAction.Resize -> action.regionId
    }
    val index = selections.indexOfFirst { it.id == id }
    if (index < 0) return
    val normalizedPosition = position.normalized(canvasSize)
    selections[index] = when (action) {
        is DragAction.Create -> {
            val start = action.start.normalized(canvasSize)
            selections[index].copy(
                left = min(start.x, normalizedPosition.x),
                top = min(start.y, normalizedPosition.y),
                right = max(start.x, normalizedPosition.x),
                bottom = max(start.y, normalizedPosition.y),
            )
        }
        is DragAction.Move -> {
            val original = action.original.normalized()
            val start = action.start.normalized(canvasSize)
            val dx = normalizedPosition.x - start.x
            val dy = normalizedPosition.y - start.y
            val width = original.right - original.left
            val height = original.bottom - original.top
            val left = (original.left + dx).coerceIn(0f, 1f - width)
            val top = (original.top + dy).coerceIn(0f, 1f - height)
            original.copy(
                left = left,
                top = top,
                right = left + width,
                bottom = top + height,
            )
        }
        is DragAction.Resize -> {
            val original = action.original.normalized()
            when (action.corner) {
                Corner.TopLeft -> original.copy(
                    left = normalizedPosition.x,
                    top = normalizedPosition.y,
                )
                Corner.TopRight -> original.copy(
                    right = normalizedPosition.x,
                    top = normalizedPosition.y,
                )
                Corner.BottomLeft -> original.copy(
                    left = normalizedPosition.x,
                    bottom = normalizedPosition.y,
                )
                Corner.BottomRight -> original.copy(
                    right = normalizedPosition.x,
                    bottom = normalizedPosition.y,
                )
            }.normalized()
        }
    }
}

private fun SelectionRegion.toPixelRect(
    size: androidx.compose.ui.unit.IntSize,
): RectF {
    val region = normalized()
    return RectF(
        region.left * size.width,
        region.top * size.height,
        region.right * size.width,
        region.bottom * size.height,
    )
}

private fun SelectionRegion.toPixelRect(size: Size): RectF {
    val region = normalized()
    return RectF(
        region.left * size.width,
        region.top * size.height,
        region.right * size.width,
        region.bottom * size.height,
    )
}

private fun RectF.corner(corner: Corner): Offset {
    return when (corner) {
        Corner.TopLeft -> Offset(left, top)
        Corner.TopRight -> Offset(right, top)
        Corner.BottomLeft -> Offset(left, bottom)
        Corner.BottomRight -> Offset(right, bottom)
    }
}

private fun Offset.normalized(size: androidx.compose.ui.unit.IntSize): Offset {
    return Offset(
        (x / size.width).coerceIn(0f, 1f),
        (y / size.height).coerceIn(0f, 1f),
    )
}

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
}
