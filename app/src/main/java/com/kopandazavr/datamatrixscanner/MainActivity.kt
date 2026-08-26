@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.kopandazavr.datamatrixscanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kopandazavr.datamatrixscanner.data.CodeRecord
import com.kopandazavr.datamatrixscanner.data.RecordStatus
import com.kopandazavr.datamatrixscanner.data.RecoveryCandidate
import com.kopandazavr.datamatrixscanner.data.ScanEvent
import com.kopandazavr.datamatrixscanner.data.StoredScanFrame
import com.kopandazavr.datamatrixscanner.scanner.DataMatrixAnalyzer
import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import com.kopandazavr.datamatrixscanner.scanner.DetectionHighlight
import com.kopandazavr.datamatrixscanner.scanner.PipelineDiagnostics
import com.kopandazavr.datamatrixscanner.scanner.axisAlignedPresentationRect
import com.kopandazavr.datamatrixscanner.ui.DataMatrixImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFF8FAFC)) {
                    ScannerApp()
                }
            }
        }
    }
}

private enum class AppMode { LIST, VIEWER, RECOVERY, LOGS, STATISTICS }
private enum class DebugLogView { RECORDING, SESSION }
private data class ViewerState(val ids: List<Long>, val index: Int, val source: RecordStatus)
private val EmbeddedCameraPreviewHeight = 348.dp

@Composable
private fun ScannerApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    LaunchedEffect(Unit) { if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var mode by remember { mutableStateOf(AppMode.LIST) }
    var debugLogView by remember { mutableStateOf(DebugLogView.RECORDING) }
    var cameraFullscreen by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    val focusMetadata = remember(vm) { FocusLensMetadataMonitor(vm.debugLogger, vm.statistics) }
    val analyzer = remember(vm, focusMetadata) {
        DataMatrixAnalyzer(
            vm::onDecoded,
            vm::onPotentialBoxes,
            vm.debugLogger,
            vm.statistics,
            focusMetadata
        )
    }
    val enhancementMode by vm.scanEnhancementMode.collectAsState()
    val executor = remember { Executors.newSingleThreadExecutor() }
    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val imageAnalysis = remember(focusMetadata) {
        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(1280, 720))
        Camera2Interop.Extender(builder).setSessionCaptureCallback(focusMetadata.captureCallback)
        builder.build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    DisposableEffect(imageAnalysis, analyzer, executor) {
        imageAnalysis.setAnalyzer(executor, analyzer)
        onDispose { imageAnalysis.clearAnalyzer() }
    }
    DisposableEffect(permissionGranted, lifecycleOwner, preview, imageCapture, imageAnalysis) {
        var boundProvider: ProcessCameraProvider? = null
        val providerFuture = if (permissionGranted) ProcessCameraProvider.getInstance(context) else null
        val listener = Runnable {
            val provider = runCatching { providerFuture?.get() }.getOrNull() ?: return@Runnable
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                    imageCapture
                )
                boundProvider = provider
            }.onFailure {
                camera = null
                vm.debugLogger.error("camera_bind_failed", "error" to it.javaClass.simpleName)
            }
        }
        providerFuture?.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            camera = null
            boundProvider?.unbind(preview, imageAnalysis, imageCapture)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            analyzer.close()
            executor.shutdown()
        }
    }
    analyzer.fullScreen = mode == AppMode.LIST && cameraFullscreen
    analyzer.active = mode == AppMode.LIST
    analyzer.enhancementMode = enhancementMode
    val focusController = rememberCameraFocusController(
        active = mode == AppMode.LIST,
        camera = camera,
        analyzer = analyzer,
        metadata = focusMetadata,
        statistics = vm.statistics,
        logger = vm.debugLogger
    )

    when (mode) {
        AppMode.LIST -> ListScreen(
            vm = vm,
            preview = preview,
            imageCapture = imageCapture,
            cameraAvailable = camera != null,
            permissionGranted = permissionGranted,
            fullscreen = cameraFullscreen,
            focusController = focusController,
            focusMetadata = focusMetadata,
            onToggleFullscreen = {
                cameraFullscreen = !cameraFullscreen
                vm.logUiEvent("camera_fullscreen", "value" to if (cameraFullscreen) 1 else 0)
            },
            analyzer = analyzer,
            onRecovery = {
                cameraFullscreen = false
                vm.logUiEvent("recovery_open")
                mode = AppMode.RECOVERY
            },
            onLogs = {
                cameraFullscreen = false
                if (vm.debugLogger.isRecording) vm.debugLogger.stopSession("open_recording_logs")
                debugLogView = DebugLogView.RECORDING
                mode = AppMode.LOGS
            },
            onSessionLogs = {
                cameraFullscreen = false
                if (vm.debugLogger.isRecording) vm.debugLogger.stopSession("open_session_logs")
                debugLogView = DebugLogView.SESSION
                mode = AppMode.LOGS
            },
            onStatistics = {
                cameraFullscreen = false
                mode = AppMode.STATISTICS
            },
            onOpenViewer = { id, ids, source ->
                vm.logUiEvent("viewer_open", "record" to id)
                viewer = ViewerState(ids, ids.indexOf(id).coerceAtLeast(0), source)
                cameraFullscreen = false
                mode = AppMode.VIEWER
            }
        )
        AppMode.VIEWER -> ViewerScreen(
            vm = vm,
            initial = requireNotNull(viewer),
            onBack = { mode = AppMode.LIST }
        )
        AppMode.RECOVERY -> RecoveryScreen(
            vm = vm,
            onBack = { mode = AppMode.LIST }
        )
        AppMode.LOGS -> DebugLogsScreen(
            logger = vm.debugLogger,
            view = debugLogView,
            onBack = { mode = AppMode.LIST }
        )
        AppMode.STATISTICS -> StatisticsScreen(
            statistics = vm.statistics,
            onBack = { mode = AppMode.LIST }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(
    vm: AppViewModel,
    preview: Preview,
    imageCapture: ImageCapture,
    cameraAvailable: Boolean,
    permissionGranted: Boolean,
    fullscreen: Boolean,
    focusController: CameraFocusController,
    focusMetadata: FocusLensMetadataMonitor,
    analyzer: DataMatrixAnalyzer,
    onToggleFullscreen: () -> Unit,
    onRecovery: () -> Unit,
    onLogs: () -> Unit,
    onSessionLogs: () -> Unit,
    onStatistics: () -> Unit,
    onOpenViewer: (Long, List<Long>, RecordStatus) -> Unit
) {
    val section by vm.section.collectAsState()
    val records by vm.records.collectAsState()
    val boxes by vm.boxes.collectAsState()
    val count by vm.setCount.collectAsState()
    val currentBatchId by vm.batchId.collectAsState()
    val debugEnabled by vm.debugEnabled.collectAsState()
    val diagnostics by analyzer.diagnostics.collectAsState()
    val logCount by vm.debugLogger.lineCount.collectAsState()
    val activeLogSession by vm.debugLogger.activeSessionId.collectAsState()
    val benchmarkState by vm.performanceBenchmarkState.collectAsState()
    val enhancementMode by vm.scanEnhancementMode.collectAsState()
    val context = LocalContext.current
    val appVersionLabel = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("?")
    }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var focusModeDialog by remember { mutableStateOf(false) }
    var benchmarkConfirm by remember { mutableStateOf(false) }
    var benchmarkViewMode by remember { mutableStateOf(BenchmarkViewMode.SHORT) }
    var photoFlash by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(RangeSelectionState()) }
    var eventRecord by remember { mutableStateOf<CodeRecord?>(null) }
    var events by remember { mutableStateOf<List<ScanEvent>>(emptyList()) }
    var topBarHeightPx by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val cameraPrefs = remember(context) { context.getSharedPreferences("camera_ui_preferences", 0) }
    var controlsMirrored by remember { mutableStateOf(cameraPrefs.getBoolean("controls_mirrored", false)) }
    var previewZoomedIn by remember { mutableStateOf(cameraPrefs.getBoolean("preview_zoomed_in", true)) }
    var heavyActive by remember { mutableStateOf(false) }
    var heavyProgress by remember { mutableStateOf(0f) }
    var debugArchiveBuilding by remember { mutableStateOf(false) }
    val heavyHaptics = remember(context) { HeavyCycleHaptics(context) }

    fun debugRuntimeSnapshot() = DebugArchiveRuntimeSnapshot(
        debugEnabled = debugEnabled,
        fullscreen = fullscreen,
        previewZoomedIn = previewZoomedIn,
        controlsMirrored = controlsMirrored,
        enhancementMode = enhancementMode.name,
        manualFocusMode = focusController.manualMode.name,
        currentBatchId = currentBatchId,
        recognizedCount = count,
        cameraAvailable = cameraAvailable,
        diagnostics = diagnostics,
        focus = focusController.control,
        focusMetadata = focusMetadata.latest()
    )

    fun createDebugMarker() {
        if (!debugEnabled) return
        val marker = vm.debugMarkers.reserve()
        haptic.performUiHaptic(UiHapticAction.DEBUG_MARKER)
        vm.debugLogger.log(
            "USER_MARKER",
            "marker" to marker.id,
            "sequence" to marker.sequence,
            "uiFile" to marker.uiFileName,
            "sourceFile" to marker.sourceFileName
        )
        val activity = context as? Activity
        val runtime = debugRuntimeSnapshot().format()
        scope.launch {
            val uiCapture = async {
                activity?.let { vm.debugMarkers.captureUi(it, marker) } ?: "missing:no_activity"
            }
            val sourceSnapshot = analyzer.awaitSnapshot(900L)
            val sourceStatus = vm.debugMarkers.saveSource(marker, sourceSnapshot)
            val uiStatus = uiCapture.await()
            vm.debugMarkers.finalize(
                marker,
                DebugMarkerFinalize(
                    uiStatus = uiStatus,
                    sourceStatus = sourceStatus,
                    frameId = sourceSnapshot?.frameId,
                    frameElapsedMs = sourceSnapshot?.frameElapsedMs,
                    sensorTimestampNs = sourceSnapshot?.sensorTimestampNs,
                    runtimeState = runtime
                )
            )
            vm.debugLogger.log(
                "MARKER_ARTIFACT",
                "marker" to marker.id,
                "sequence" to marker.sequence,
                "uiStatus" to uiStatus,
                "sourceStatus" to sourceStatus,
                "frame" to sourceSnapshot?.frameId,
                "sensorTsNs" to sourceSnapshot?.sensorTimestampNs
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { heavyHaptics.cancel() }
    }

    LaunchedEffect(benchmarkState) {
        analyzer.benchmarkPaused = benchmarkState is PerformanceBenchmarkState.Running
    }

    fun startHeavyCycle() {
        if (heavyActive) return
        heavyActive = true
        heavyProgress = .02f
        val cycleId = SystemClock.elapsedRealtime()
        scope.launch {
            heavyHaptics.start()
            try {
                vm.logUiEvent("boost_cycle_start", "cycle" to cycleId)
                vm.debugLogger.log("HEAVY_STAGE", "cycle" to cycleId, "stage" to "evidence_window", "focusCommands" to 0)
                heavyProgress = .18f
                val result = analyzer.runBoost(cycleId, timeoutMs = 20_000L)
                heavyProgress = .96f
                heavyProgress = 1f
                vm.debugLogger.log(
                    "HEAVY_STAGE",
                    "cycle" to cycleId,
                    "stage" to "complete",
                    "success" to if (result.completed > 0) 1 else 0,
                    "submitted" to result.submitted,
                    "decoded" to result.decoded,
                    "reason" to result.reason,
                    "focusCommands" to 0
                )
                delay(140L)
            } catch (t: Throwable) {
                vm.debugLogger.error("heavy_cycle_exception", "cycle" to cycleId, "error" to t.javaClass.simpleName)
            } finally {
                heavyHaptics.finish()
                heavyActive = false
                heavyProgress = 0f
            }
        }
    }

    LaunchedEffect(section) { selection = RangeSelectionState() }
    LaunchedEffect(section, records.firstOrNull()?.id, records.firstOrNull()?.lastScanAt) {
        if (section == RecordStatus.ACTIVE && records.isNotEmpty()) listState.animateScrollToItem(0)
    }
    androidx.activity.compose.BackHandler(enabled = fullscreen) {
        haptic.performUiHaptic(UiHapticAction.FULLSCREEN_TOGGLE)
        onToggleFullscreen()
    }

    Box(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Scaffold(
                topBar = {
                    Column {
                        // Measure only the visible toolbar/debug area. The preview spacer below
                        // reserves list space and must not be included in the camera Y offset.
                        Column(Modifier.onSizeChanged { topBarHeightPx = it.height }) {
                            TopAppBar(
                            title = { Text(section.title()) },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF8FAFC)),
                            actions = {
                                RecordStatus.entries.forEach { item ->
                                    SectionButton(
                                        status = item,
                                        selected = section == item,
                                        onClick = { vm.setSection(item) }
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Версия: $appVersionLabel") },
                                            onClick = { },
                                            enabled = false
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Восстановить из фото") },
                                            onClick = { menu = false; if (!heavyActive) onRecovery() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Ручной фокус: ${focusController.manualMode.title}") },
                                            onClick = { menu = false; focusModeDialog = true }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (debugEnabled) "Отладка: вкл" else "Отладка: выкл") },
                                            onClick = { menu = false; vm.toggleDebug() }
                                        )
                                        if (debugEnabled) {
                                            DropdownMenuItem(
                                                text = { Text("Логи записи") },
                                                onClick = { menu = false; if (!heavyActive) onLogs() }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Логи сессии") },
                                                onClick = { menu = false; if (!heavyActive) onSessionLogs() }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Статистика") },
                                                onClick = {
                                                    menu = false
                                                    if (!heavyActive) {
                                                        haptic.performUiHaptic(UiHapticAction.MENU_ACTION)
                                                        onStatistics()
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (debugArchiveBuilding) "Отладочный архив…" else "Отладочный архив") },
                                                enabled = !debugArchiveBuilding && !heavyActive,
                                                onClick = {
                                                    menu = false
                                                    if (!debugArchiveBuilding && !heavyActive) {
                                                        debugArchiveBuilding = true
                                                        haptic.performUiHaptic(UiHapticAction.DEBUG_ARCHIVE)
                                                        vm.debugLogger.log("DEBUG_ARCHIVE", "stage" to "start")
                                                        val runtime = debugRuntimeSnapshot()
                                                        scope.launch {
                                                            runCatching { vm.buildDebugArchive(runtime) }
                                                                .onSuccess { file ->
                                                                    vm.debugLogger.log("DEBUG_ARCHIVE", "stage" to "ready", "file" to file.name, "size" to file.length())
                                                                    shareDebugArchive(context, file)
                                                                }
                                                                .onFailure { error ->
                                                                    vm.debugLogger.error("debug_archive", "error" to error.javaClass.simpleName)
                                                                    Toast.makeText(context, "Не удалось собрать отладочный архив", Toast.LENGTH_LONG).show()
                                                                }
                                                            debugArchiveBuilding = false
                                                        }
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Очистить marker-снимки") },
                                                enabled = !debugArchiveBuilding,
                                                onClick = {
                                                    menu = false
                                                    haptic.performUiHaptic(UiHapticAction.LOG_CLEAR)
                                                    scope.launch {
                                                        val removed = vm.debugMarkers.clear()
                                                        Toast.makeText(context, "Удалено файлов marker: $removed", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Тест производительности") },
                                                onClick = {
                                                    menu = false
                                                    if (!heavyActive && benchmarkState !is PerformanceBenchmarkState.Running) benchmarkConfirm = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                            if (debugEnabled) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .background(Color(0xFFE2E8F0))
                                ) {
                                    val actionAlignment = if (controlsMirrored) Alignment.CenterStart else Alignment.CenterEnd
                                    val infoAlignment = if (controlsMirrored) Alignment.CenterEnd else Alignment.CenterStart
                                    IconButton(
                                        onClick = {
                                            haptic.performUiHaptic(UiHapticAction.LOG_PLAY_PAUSE)
                                            if (activeLogSession == null) {
                                                vm.debugLogger.startSession(
                                                    "camera" to "back_1280x720",
                                                    "focusMode" to focusController.manualMode.name,
                                                    "roi" to "40",
                                                    "pipeline" to "zxing+candidate_ml+heavy",
                                                    "fullscreen" to if (fullscreen) 1 else 0
                                                )
                                            } else {
                                                vm.debugLogger.stopSession("pause")
                                            }
                                        },
                                        modifier = Modifier.align(actionAlignment).padding(horizontal = 6.dp).size(38.dp)
                                    ) {
                                        Icon(
                                            if (activeLogSession == null) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            if (activeLogSession == null) "Запустить лог" else "Пауза"
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptic.performUiHaptic(UiHapticAction.LOG_CLEAR)
                                            vm.debugLogger.clear()
                                        },
                                        modifier = Modifier.align(Alignment.Center).size(38.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteSweep, "Очистить логи")
                                    }
                                    Text(
                                        "$logCount · ${if (activeLogSession == null) "Pause" else "REC S#$activeLogSession"}",
                                        modifier = Modifier.align(infoAlignment).padding(horizontal = 10.dp),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (activeLogSession == null) Color.Gray else Color(0xFFB91C1C)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(EmbeddedCameraPreviewHeight))
                    }
                },
                bottomBar = {
                    if (selection.isActive) {
                        val orderedIds = records.map(CodeRecord::id)
                        SelectionBar(
                            section = section,
                            selected = selection.selected,
                            allSelected = orderedIds.isNotEmpty() && orderedIds.all { it in selection.selected },
                            onToggleAll = { selection = selection.toggleAll(orderedIds) },
                            onMove = { target ->
                                if (target == RecordStatus.ARCHIVED) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                vm.move(selection.selected, target)
                                selection = RangeSelectionState()
                            }
                        )
                    }
                }
            ) { padding ->
                if (records.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(if (section == RecordStatus.ACTIVE) "Направьте камеру на Data Matrix" else "Здесь пока пусто", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
                        items(records, key = { it.id }) { record ->
                            RecordRow(
                                record = record,
                                currentBatch = record.status == RecordStatus.ACTIVE && record.batchId == currentBatchId,
                                selected = record.id in selection.selected,
                                selectionMode = selection.isActive,
                                onLongClick = {
                                    selection = selection.selectRange(record.id, records.map(CodeRecord::id))
                                },
                                onClick = {
                                    selection = selection.toggle(record.id, records.map(CodeRecord::id))
                                },
                                onMatrix = { if (!heavyActive) onOpenViewer(record.id, records.map(CodeRecord::id), section) },
                                onStatus = {
                                    eventRecord = record
                                    vm.events(record.id) { events = it }
                                },
                                onScanned = { vm.setScanned(record.id, it) }
                            )
                        }
                    }
                }
            }
        }

        val cameraModifier = if (fullscreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxWidth().height(EmbeddedCameraPreviewHeight).offset { IntOffset(0, topBarHeightPx) }
        }
        CameraPreview(
            preview = preview,
            cameraAvailable = cameraAvailable,
            permissionGranted = permissionGranted,
            boxes = boxes,
            modifier = cameraModifier,
            fullscreen = fullscreen,
            onClick = {
                haptic.performUiHaptic(UiHapticAction.FULLSCREEN_TOGGLE)
                onToggleFullscreen()
            },
            onBack = {
                haptic.performUiHaptic(UiHapticAction.FULLSCREEN_TOGGLE)
                onToggleFullscreen()
            },
            onPhoto = {
                if (!heavyActive) {
                    if (!cameraAvailable) {
                        Toast.makeText(context, "Камера недоступна", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.logUiEvent("photo")
                        haptic.performUiHaptic(UiHapticAction.MENU_ACTION)
                        photoFlash = true
                        scope.launch {
                            delay(90L)
                            photoFlash = false
                        }
                        saveCameraPhoto(context, imageCapture) { result ->
                            Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onHeavyCycle = ::startHeavyCycle,
            heavyActive = heavyActive,
            heavyProgress = heavyProgress,
            focusController = focusController,
            recognizedCount = count,
            onNextBatch = {
                if (!heavyActive) {
                    haptic.performUiHaptic(UiHapticAction.NAVIGATION)
                    vm.nextBatch()
                }
            },
            onDebugMarker = if (debugEnabled) ::createDebugMarker else null,
            debugEnabled = debugEnabled,
            diagnostics = diagnostics,
            controlsMirrored = controlsMirrored,
            onSwapControls = {
                haptic.performUiHaptic(UiHapticAction.CONTROL_SWAP)
                controlsMirrored = !controlsMirrored
                cameraPrefs.edit().putBoolean("controls_mirrored", controlsMirrored).apply()
                vm.logUiEvent("controls_swap", "mirrored" to if (controlsMirrored) 1 else 0)
            },
            previewZoomedIn = previewZoomedIn,
            onTogglePreviewZoom = {
                haptic.performUiHaptic(UiHapticAction.ZOOM_TOGGLE)
                previewZoomedIn = !previewZoomedIn
                cameraPrefs.edit().putBoolean("preview_zoomed_in", previewZoomedIn).apply()
                vm.logUiEvent(
                    "preview_fit", "zoomedIn" to if (previewZoomedIn) 1 else 0,
                    "fit" to if (previewZoomedIn) "height" else "width"
                )
            }
        )
        if (photoFlash) {
            Box(Modifier.fillMaxSize().background(Color.White))
        }
    }

    eventRecord?.let { record ->
        ModalBottomSheet(onDismissRequest = { eventRecord = null; events = emptyList() }) {
            Text(
                if (record.isDuplicate) "Журнал — Дубликат" else "Журнал — Уникальный",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (events.isEmpty()) Text("Загрузка…", Modifier.padding(20.dp))
            events.forEach { event ->
                Text("${formatTime(event.timestamp)} — ${event.type.displayName}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (focusModeDialog) {
        ManualFocusModeDialog(
            selected = focusController.manualMode,
            onSelect = {
                focusController.onManualModeChange(it)
                focusModeDialog = false
            },
            onDismiss = { focusModeDialog = false }
        )
    }

    if (benchmarkConfirm) {
        AlertDialog(
            onDismissRequest = { benchmarkConfirm = false },
            title = { Text("Запустить тест производительности?") },
            text = {
                Text("Распознавание с камеры будет временно приостановлено. Тест прогревает декодеры, перебирает число worker-ов и выполняет sustained-проверку; устройство может нагреться.")
            },
            confirmButton = {
                TextButton(onClick = {
                    benchmarkConfirm = false
                    scope.launch {
                        val snapshot = analyzer.awaitSnapshot(1_800L)?.bitmap
                        analyzer.benchmarkPaused = true
                        vm.startPerformanceBenchmark(snapshot)
                    }
                }) { Text("Запустить") }
            },
            dismissButton = { TextButton(onClick = { benchmarkConfirm = false }) { Text("Отмена") } }
        )
    }

    when (val state = benchmarkState) {
        is PerformanceBenchmarkState.Running -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Тест производительности") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) })
                    Spacer(Modifier.height(12.dp))
                    Text("${state.phase} · workers=${state.workers} · ${(state.progress * 100).toInt()}%")
                }
            },
            confirmButton = {
                if (state.cancellable) TextButton(onClick = vm::cancelPerformanceBenchmark) { Text("Остановить") }
            }
        )
        is PerformanceBenchmarkState.Completed -> {
            val representation = state.result.format(benchmarkViewMode)
            AlertDialog(
                onDismissRequest = vm::dismissPerformanceBenchmarkResult,
                title = { Text("Результат теста") },
                text = {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { benchmarkViewMode = BenchmarkViewMode.SHORT }) { Text("Кратко") }
                            TextButton(onClick = { benchmarkViewMode = BenchmarkViewMode.FULL }) { Text("Полностью") }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(representation))
                                Toast.makeText(context, "Результат скопирован", Toast.LENGTH_SHORT).show()
                            }) { Text("Копировать") }
                        }
                        SelectionContainer {
                            Text(representation, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::dismissPerformanceBenchmarkResult) { Text("Закрыть") }
                }
            )
        }
        is PerformanceBenchmarkState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissPerformanceBenchmarkResult,
            title = { Text("Тест не завершён") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = vm::dismissPerformanceBenchmarkResult) { Text("Закрыть") } }
        )
        PerformanceBenchmarkState.Idle -> Unit
    }
}

@Composable
private fun ManualFocusModeDialog(
    selected: ManualFocusMode,
    onSelect: (ManualFocusMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ручной фокус") },
        text = {
            Column {
                Text("Обычное нажатие запускает выбранный ручной поиск дистанции линзы; удержание кнопки 500 мс запускает нативный автофокус по центральной области. На ручных позициях учитываются фактическое состояние линзы, резкость под прицелом и устойчивость областей Data Matrix.")
                Spacer(Modifier.height(10.dp))
                ManualFocusMode.entries.forEach { mode ->
                    Surface(
                        onClick = { onSelect(mode) },
                        color = if (selected == mode) Color(0xFFDDEAFE) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(mode.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (mode == ManualFocusMode.FAST) "Короткий ручной перебор без уточняющего прохода" else "Более плотный перебор + уточнение лучшей дистанции",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            if (selected == mode) Icon(Icons.Default.Check, null, tint = Color(0xFF2563EB))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun SectionButton(status: RecordStatus, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 2.dp).size(42.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFFDDEAFE) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, Color(0xFF60A5FA)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (status) {
                    RecordStatus.ACTIVE -> Icons.Default.Visibility
                    RecordStatus.ARCHIVED -> Icons.Default.Archive
                    RecordStatus.TRASH -> Icons.Default.Delete
                },
                contentDescription = status.title(),
                tint = if (selected) Color(0xFF1D4ED8) else Color(0xFF475569),
                modifier = Modifier.size(25.dp)
            )
        }
    }
}

@Composable
private fun CameraPreview(
    preview: Preview,
    cameraAvailable: Boolean,
    permissionGranted: Boolean,
    boxes: List<DetectionBox>,
    modifier: Modifier,
    fullscreen: Boolean,
    onClick: () -> Unit,
    onBack: () -> Unit,
    onPhoto: (() -> Unit)? = null,
    onHeavyCycle: (() -> Unit)? = null,
    heavyActive: Boolean = false,
    heavyProgress: Float = 0f,
    focusController: CameraFocusController? = null,
    recognizedCount: Int? = null,
    onNextBatch: (() -> Unit)? = null,
    onDebugMarker: (() -> Unit)? = null,
    debugEnabled: Boolean = false,
    diagnostics: PipelineDiagnostics = PipelineDiagnostics(),
    controlsMirrored: Boolean = false,
    onSwapControls: (() -> Unit)? = null,
    previewZoomedIn: Boolean = true,
    onTogglePreviewZoom: (() -> Unit)? = null
) {
    val cameraFrameModifier = modifier
        .clipToBounds()
        .background(Color.Black)
        .then(if (heavyActive) Modifier.border(3.dp, Color(0xFF3B82F6)) else Modifier)
    var diagnosticNow by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var showDiagnosticsHelp by remember { mutableStateOf(false) }
    LaunchedEffect(debugEnabled, diagnostics.totalJobs, diagnostics.updatedAtElapsedMs) {
        diagnosticNow = SystemClock.elapsedRealtime()
        while (debugEnabled && diagnostics.totalJobs > 0) {
            delay(100L)
            diagnosticNow = SystemClock.elapsedRealtime()
        }
    }
    val liveDiagnosticAge = if (diagnostics.totalJobs > 0 && diagnostics.updatedAtElapsedMs > 0L) {
        diagnostics.oldestFrameAgeMs + (diagnosticNow - diagnostics.updatedAtElapsedMs).coerceAtLeast(0L)
    } else {
        diagnostics.oldestFrameAgeMs
    }

    BoxWithConstraints(cameraFrameModifier, contentAlignment = Alignment.Center) {
        if (cameraAvailable) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = when {
                        fullscreen -> PreviewView.ScaleType.FIT_CENTER
                        previewZoomedIn -> PreviewView.ScaleType.FILL_CENTER
                        else -> PreviewView.ScaleType.FIT_CENTER
                    }
                    preview.setSurfaceProvider(surfaceProvider)
                } },
                update = {
                    it.scaleType = when {
                        fullscreen -> PreviewView.ScaleType.FIT_CENTER
                        previewZoomedIn -> PreviewView.ScaleType.FILL_CENTER
                        else -> PreviewView.ScaleType.FIT_CENTER
                    }
                },
                modifier = Modifier.align(Alignment.Center).then(
                    if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().requiredHeight(EmbeddedCameraPreviewHeight)
                )
            )
        } else {
            Text(if (permissionGranted) "Камера недоступна" else "Нужно разрешение камеры", color = Color.White)
        }

        DetectionOverlay(boxes, rawPotential = debugEnabled, fitContent = fullscreen || !previewZoomedIn)
        CameraAimOverlay(active = heavyActive)
        Box(Modifier.fillMaxSize().clickable(onClick = onClick))

        focusController?.takeIf { it.control.minimumFocusDistance != null }?.let { focus ->
            FocusDistanceControl(
                focus = focus,
                fullscreen = fullscreen,
                modifier = Modifier
                    .align(if (controlsMirrored) Alignment.TopStart else Alignment.TopEnd)
                    .then(if (fullscreen) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)
                    .padding(horizontal = 8.dp, vertical = if (fullscreen) 64.dp else 8.dp)
            )
        }

        if (debugEnabled) {
            Surface(
                onClick = { showDiagnosticsHelp = true },
                modifier = Modifier
                    .align(if (controlsMirrored) Alignment.TopStart else Alignment.TopEnd)
                    .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier)
                    .padding(
                        top = if (fullscreen) 64.dp else 8.dp,
                        start = if (controlsMirrored) 62.dp else 0.dp,
                        end = if (controlsMirrored) 0.dp else 62.dp
                    ),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = .62f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .32f))
            ) {
                Text(
                    "jobs ${diagnostics.totalJobs} ($liveDiagnosticAge ms)\n" +
                        "F:${diagnostics.fastJobs} ML:${diagnostics.mlPending}/${diagnostics.mlInFlight} H:${diagnostics.heavyPending}/${diagnostics.heavyInFlight}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                )
            }
        }

        if (fullscreen) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                Text("Распознано: ${recognizedCount ?: 0}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }

            onNextBatch?.let { action ->
                Button(
                    onClick = action,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().padding(16.dp).height(64.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("След. набор", fontSize = 20.sp) }
            }

            Row(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                    onHeavyCycle?.let { action ->
                        EnhancementCycleButton(
                            fullscreen = true,
                            active = heavyActive,
                            progress = heavyProgress,
                            onClick = action
                        )
                    }
                }
                onPhoto?.let { action -> CameraPhotoButton(fullscreen = true, onClick = action) }
                    ?: Spacer(Modifier.size(93.dp))
                Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                    focusController?.let { focus ->
                        FocusModeButton(
                            fullscreen = true,
                            busy = focus.busy || heavyActive,
                            nativeAfActive = focus.nativeAfActive,
                            nominalProgressMs = focus.nominalProgressMs,
                            onTap = focus.onTap,
                            onLongPress = focus.onLongPress
                        )
                    }
                }
            }
        } else {
            if (recognizedCount != null) {
                Surface(
                    modifier = Modifier
                        .align(if (controlsMirrored) Alignment.TopEnd else Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = Color.Black.copy(alpha = 0.46f)
                ) {
                    Text(
                        "Распознано: $recognizedCount",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            onPhoto?.let { action ->
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                    CameraPhotoButton(fullscreen = false, onClick = action)
                }
            }

            val mainAlignment = if (controlsMirrored) Alignment.BottomStart else Alignment.BottomEnd
            Row(
                modifier = Modifier.align(mainAlignment).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (controlsMirrored) {
                    focusController?.let { focus ->
                        FocusModeButton(
                            fullscreen = false,
                            busy = focus.busy || heavyActive,
                            nativeAfActive = focus.nativeAfActive,
                            nominalProgressMs = focus.nominalProgressMs,
                            onTap = focus.onTap,
                            onLongPress = focus.onLongPress
                        )
                    }
                    onHeavyCycle?.let { action ->
                        EnhancementCycleButton(
                            fullscreen = false,
                            active = heavyActive,
                            progress = heavyProgress,
                            onClick = action
                        )
                    }
                } else {
                    onHeavyCycle?.let { action ->
                        EnhancementCycleButton(
                            fullscreen = false,
                            active = heavyActive,
                            progress = heavyProgress,
                            onClick = action
                        )
                    }
                    focusController?.let { focus ->
                        FocusModeButton(
                            fullscreen = false,
                            busy = focus.busy || heavyActive,
                            nativeAfActive = focus.nativeAfActive,
                            nominalProgressMs = focus.nominalProgressMs,
                            onTap = focus.onTap,
                            onLongPress = focus.onLongPress
                        )
                    }
                }
            }

            if (onNextBatch != null || onSwapControls != null || onDebugMarker != null) {
                val utilityAlignment = if (controlsMirrored) Alignment.BottomEnd else Alignment.BottomStart
                Column(
                    modifier = Modifier.align(utilityAlignment).padding(6.dp),
                    horizontalAlignment = if (controlsMirrored) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    onDebugMarker?.let { DebugMarkerButton(it) }
                    onTogglePreviewZoom?.let { PreviewZoomButton(previewZoomedIn, it) }
                    onSwapControls?.let { SwapControlsButton(it) }
                    onNextBatch?.let { action ->
                        Surface(
                            onClick = action,
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Black.copy(alpha = 0.46f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = .56f))
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                Text("След. набор", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDiagnosticsHelp) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsHelp = false },
            title = { Text("Pipeline — обозначения") },
            text = {
                Text(
                    "jobs — все текущие задачи; число в скобках — возраст самого старого кадра.\n\n" +
                        "F — быстрый ZXing-проход.\n" +
                        "ML a/b — ожидающие / выполняемые ML Kit задачи.\n" +
                        "H a/b — ожидающие / выполняемые дорогие Boost-задачи.\n\n" +
                        "Панель диагностическая: она ничего не запускает и не управляет фокусом."
                )
            },
            confirmButton = { TextButton(onClick = { showDiagnosticsHelp = false }) { Text("Понятно") } }
        )
    }
}

@Composable
private fun DebugMarkerButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(66.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color(0xFF7C3AED).copy(alpha = .90f),
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = .82f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("!", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun PreviewZoomButton(zoomedIn: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(66.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (zoomedIn) Color(0xFF2563EB) else Color.Black.copy(alpha = .50f),
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = .72f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (zoomedIn) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                if (zoomedIn) "Уменьшить: вписать по ширине" else "Увеличить: вписать по высоте",
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }
    }
}

@Composable
private fun FocusDistanceControl(
    focus: CameraFocusController,
    fullscreen: Boolean,
    modifier: Modifier = Modifier
) {
    val control = focus.control
    val minimum = control.minimumFocusDistance ?: return
    val haptic = LocalHapticFeedback.current
    var sliderHeightPx by remember { mutableStateOf(1) }
    fun setFromY(y: Float) {
        lensDistanceFromSlider((y / sliderHeightPx.coerceAtLeast(1)).coerceIn(0f, 1f), minimum)
            ?.let(focus.onSliderTarget)
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            onClick = {
                haptic.performUiHaptic(UiHapticAction.HOME_TOGGLE)
                focus.onHomeToggle()
            },
            enabled = control.homeToggleEnabled,
            modifier = Modifier.size(44.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = if (control.homeAvailable) Color(0xFF2563EB) else Color.Black.copy(alpha = .48f),
            border = BorderStroke(1.5.dp, Color.White.copy(alpha = .82f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Home,
                    if (control.homeAvailable) "Изменить или очистить HOME" else "Сохранить HOME",
                    tint = Color.White.copy(alpha = if (control.homeToggleEnabled) 1f else .38f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Canvas(
            Modifier
                .width(48.dp)
                .height(if (fullscreen) 280.dp else 184.dp)
                .onSizeChanged { sliderHeightPx = it.height.coerceAtLeast(1) }
                .pointerInput(minimum) {
                    detectTapGestures { offset -> setFromY(offset.y) }
                }
                .pointerInput(minimum) {
                    detectDragGestures(
                        onDragStart = { setFromY(it.y) },
                        onDrag = { change, _ ->
                            change.consume()
                            setFromY(change.position.y)
                        }
                    )
                }
        ) {
            val centerX = size.width / 2f
            drawLine(
                color = Color.White.copy(alpha = .72f),
                start = Offset(centerX, 8.dp.toPx()),
                end = Offset(centerX, size.height - 8.dp.toPx()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            control.normalizedHome?.let { normalized ->
                drawCircle(
                    color = Color(0xFF2563EB).copy(alpha = if (control.nativeAfActive) .5f else 1f),
                    radius = 10.dp.toPx(),
                    center = Offset(centerX, normalized * size.height)
                )
            }
            control.normalizedRequested?.let { normalized ->
                drawCircle(
                    color = Color.White,
                    radius = 7.dp.toPx(),
                    center = Offset(centerX, normalized * size.height)
                )
            }
            control.normalizedActual?.let { normalized ->
                drawCircle(
                    color = Color.White.copy(alpha = if (control.actualStale) .46f else 1f),
                    radius = 10.dp.toPx(),
                    center = Offset(centerX, normalized * size.height),
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = if (control.actualStale) {
                            PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                        } else null
                    )
                )
            }
        }
    }
}

@Composable
private fun CameraPhotoButton(fullscreen: Boolean, onClick: () -> Unit) {
    val size = if (fullscreen) 93.dp else 66.dp
    val iconSize = if (fullscreen) 48.dp else 36.dp
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color.Black.copy(alpha = if (fullscreen) .42f else .50f),
        border = BorderStroke(2.dp, Color.White.copy(alpha = .82f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.CameraAlt, "Сделать фото", tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun SwapControlsButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(66.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color.Black.copy(alpha = .50f),
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = .72f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.SwapHoriz, "Поменять сторону управления", tint = Color.White, modifier = Modifier.size(38.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(
    record: CodeRecord,
    currentBatch: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onMatrix: () -> Unit,
    onStatus: () -> Unit,
    onScanned: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val platformConfiguration = LocalViewConfiguration.current
    val halfSecondLongPress = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
    val selectionTap = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
    val selectionLong = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongClick()
    }

    CompositionLocalProvider(LocalViewConfiguration provides halfSecondLongPress) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).height(128.dp),
            shape = RoundedCornerShape(12.dp),
            color = when {
                selected -> Color(0xFFDDEAFE)
                currentBatch -> Color(0xFFE0EEFF)
                else -> Color.White
            },
            border = BorderStroke(
                1.dp,
                when {
                    selected -> Color(0xFF60A5FA)
                    currentBatch -> Color(0xFF93C5FD)
                    else -> Color(0xFFD7DEE8)
                }
            )
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = { if (selectionMode) selectionTap() },
                        onLongClick = selectionLong
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.combinedClickable(
                        onClick = { if (selectionMode) selectionTap() else onMatrix() },
                        onLongClick = selectionLong
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DataMatrixImage(record.rawBytes, record.isGs1, 80.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "№${record.id}",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(record.displayText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatTime(record.lastScanAt), fontSize = 13.sp, color = Color.DarkGray)
                    StatusTag(
                        duplicate = record.isDuplicate,
                        duplicateCount = record.duplicateCount,
                        onClick = { if (selectionMode) selectionTap() else onStatus() }
                    )
                }
                Button(
                    onClick = {
                        if (selectionMode) {
                            selectionTap()
                        } else {
                            performStrongActionHaptic(context)
                            onScanned(true)
                        }
                    },
                    enabled = selectionMode || record.status != RecordStatus.ARCHIVED,
                    modifier = Modifier.width(72.dp).fillMaxHeight(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Icon(
                        if (selectionMode && selected) Icons.Default.Check else Icons.Default.Archive,
                        if (selectionMode) "Выбрать" else "В архив",
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusTag(duplicate: Boolean, duplicateCount: Int? = null, onClick: (() -> Unit)? = null) {
    val background = if (duplicate) Color(0xFFFEE2E2) else Color(0xFFDCFCE7)
    val foreground = if (duplicate) Color(0xFFB91C1C) else Color(0xFF15803D)
    val label = if (duplicate) {
        duplicateCount?.let { "Дубликат ×$it" } ?: "Дубликат"
    } else {
        "Уникальный"
    }
    val interactionModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(background)
            .then(interactionModifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = foreground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SelectionBar(
    section: RecordStatus,
    selected: Set<Long>,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onMove: (RecordStatus) -> Unit
) {
    BottomAppBar(containerColor = Color(0xFFEFF6FF)) {
        Text("Выбрано: ${selected.size}", Modifier.padding(horizontal = 12.dp).weight(1f), fontWeight = FontWeight.Bold)
        SelectionActionButton(
            icon = Icons.Default.SelectAll,
            contentDescription = if (allSelected) "Снять выделение со всех" else "Выбрать всё",
            selected = allSelected,
            onClick = onToggleAll
        )
        when (section) {
            RecordStatus.ACTIVE -> {
                SelectionActionButton(Icons.Default.Archive, "В архив") { onMove(RecordStatus.ARCHIVED) }
                SelectionActionButton(Icons.Default.Delete, "Удалить") { onMove(RecordStatus.TRASH) }
            }
            RecordStatus.ARCHIVED -> {
                SelectionActionButton(Icons.Default.Restore, "Вернуть") { onMove(RecordStatus.ACTIVE) }
                SelectionActionButton(Icons.Default.Delete, "В корзину") { onMove(RecordStatus.TRASH) }
            }
            RecordStatus.TRASH -> {
                SelectionActionButton(Icons.Default.Restore, "Вернуть") { onMove(RecordStatus.ACTIVE) }
                SelectionActionButton(Icons.Default.Archive, "В архив") { onMove(RecordStatus.ARCHIVED) }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun SelectionActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 3.dp).size(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFFDDEAFE) else Color.White,
        border = BorderStroke(1.dp, if (selected) Color(0xFF60A5FA) else Color(0xFF94A3B8))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription,
                tint = if (selected) Color(0xFF1D4ED8) else Color(0xFF334155),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun DetectionOverlay(
    boxes: List<DetectionBox>,
    rawPotential: Boolean = false,
    fitContent: Boolean = false
) {
    Canvas(Modifier.fillMaxSize()) {
        boxes.forEach { box ->
            val canvasAspect = size.width / size.height.coerceAtLeast(1f)
            val contentWidth: Float
            val contentHeight: Float
            val left: Float
            val top: Float
            // Match PreviewView: embedded uses FILL_CENTER, fullscreen uses FIT_CENTER so
            // switching modes preserves the camera field of view instead of zooming it.
            if ((canvasAspect > box.imageAspect) != fitContent) {
                contentWidth = size.width
                contentHeight = contentWidth / box.imageAspect
                left = 0f
                top = (size.height - contentHeight) / 2f
            } else {
                contentHeight = size.height
                contentWidth = contentHeight * box.imageAspect
                left = (size.width - contentWidth) / 2f
                top = 0f
            }
            val (color, strokeWidth) = when (box.highlight) {
                DetectionHighlight.POTENTIAL -> Color.White to if (rawPotential) 3.5f else 5.2f
                DetectionHighlight.ACTIVE -> Color(0xFF22C55E) to 6f
                DetectionHighlight.DUPLICATE -> Color(0xFFFACC15) to 6f
            }
            val overlayAlpha = box.overlayAlpha.coerceIn(0f, 1f)

            if (!rawPotential) {
                // Production overlay is deliberately screen-axis aligned. Expand the actual
                // decoder/candidate bounds by 10% overall, then use the same colour for a
                // ~50%-opaque fill and a slightly rounded outline.
                val rect = box.axisAlignedPresentationRect() ?: return@forEach
                val x = left + rect.left * contentWidth
                val y = top + rect.top * contentHeight
                val width = (rect.right - rect.left) * contentWidth
                val height = (rect.bottom - rect.top) * contentHeight
                val radius = minOf(9.dp.toPx(), width / 5f, height / 5f).coerceAtLeast(1f)
                val topLeft = Offset(x, y)
                val drawSize = androidx.compose.ui.geometry.Size(width, height)
                val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                drawRoundRect(
                    color = color.copy(alpha = .50f * overlayAlpha),
                    topLeft = topLeft,
                    size = drawSize,
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = color.copy(alpha = overlayAlpha),
                    topLeft = topLeft,
                    size = drawSize,
                    cornerRadius = corner,
                    style = Stroke(width = strokeWidth)
                )
            } else {
                val path = Path()
                box.points.forEachIndexed { index, point ->
                    val x = left + point.x * contentWidth
                    val y = top + point.y * contentHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val fillAlpha = if (box.highlight == DetectionHighlight.POTENTIAL) 0f else .055f
                if (fillAlpha > 0f) drawPath(path, color.copy(alpha = fillAlpha * overlayAlpha))
                drawPath(path, color.copy(alpha = overlayAlpha), style = Stroke(width = strokeWidth))
            }
        }
    }
}

@Composable
private fun CameraAimOverlay(active: Boolean = false) {
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val arm = 14.dp.toPx()
        val foreground = if (active) Color(0xFF3B82F6) else Color.White
        drawLine(Color.Black.copy(alpha = .5f), Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Color.Black.copy(alpha = .5f), Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 3.dp.toPx(), StrokeCap.Round)
        drawLine(foreground, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 1.4.dp.toPx(), StrokeCap.Round)
        drawLine(foreground, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 1.4.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun StoredFrameImage(frame: StoredScanFrame, modifier: Modifier = Modifier) {
    val bitmap = remember(frame.id) {
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)?.asImageBitmap()
    }
    var scale by remember(frame.id) { mutableStateOf(1f) }
    var translation by remember(frame.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember(frame.id) { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged { viewport = it }
            .pointerInput(frame.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    val maxX = viewport.width * (nextScale - 1f) / 2f
                    val maxY = viewport.height * (nextScale - 1f) / 2f
                    scale = nextScale
                    translation = Offset(
                        x = (translation.x + pan.x).coerceIn(-maxX, maxX),
                        y = (translation.y + pan.y).coerceIn(-maxY, maxY)
                    )
                    if (nextScale == 1f) translation = Offset.Zero
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = translation.x
                    translationY = translation.y
                }
        ) {
            bitmap?.let {
                Image(it, "Кадр распознавания", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Canvas(Modifier.fillMaxSize()) {
                if (frame.box.size < 4) return@Canvas
                val imageAspect = frame.width.toFloat() / frame.height.coerceAtLeast(1)
                val canvasAspect = size.width / size.height.coerceAtLeast(1f)
                val contentWidth: Float
                val contentHeight: Float
                val left: Float
                val top: Float
                if (canvasAspect > imageAspect) {
                    contentHeight = size.height
                    contentWidth = contentHeight * imageAspect
                    left = (size.width - contentWidth) / 2f
                    top = 0f
                } else {
                    contentWidth = size.width
                    contentHeight = contentWidth / imageAspect
                    left = 0f
                    top = (size.height - contentHeight) / 2f
                }
                val centerX = frame.box.map { it.x }.average().toFloat()
                val centerY = frame.box.map { it.y }.average().toFloat()
                val expanded = frame.box.map { point ->
                    // Expand away from the symbol. Do not clamp to the image edge: a
                    // clipped outer border is safer than painting over Data Matrix modules.
                    val x = centerX + (point.x - centerX) * 1.14f
                    val y = centerY + (point.y - centerY) * 1.14f
                    Offset(left + x * contentWidth, top + y * contentHeight)
                }
                val path = Path()
                expanded.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                drawPath(path, Color(0xFF22C55E), style = Stroke(width = 5f / scale))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLogsScreen(logger: PipelineDebugLogger, view: DebugLogView, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val recordingVersion by logger.linesVersion.collectAsState()
    val sessionVersion by logger.sessionLinesVersion.collectAsState()
    val recordingCount by logger.lineCount.collectAsState()
    val sessionCount by logger.sessionLineCount.collectAsState()
    val version = if (view == DebugLogView.RECORDING) recordingVersion else sessionVersion
    val count = if (view == DebugLogView.RECORDING) recordingCount else sessionCount
    fun snapshotLines(): List<String> = if (view == DebugLogView.RECORDING) logger.snapshotLines() else logger.snapshotSessionLines()
    fun snapshotText(): String = if (view == DebugLogView.RECORDING) logger.snapshotText() else logger.snapshotSessionText()
    var lines by remember(view) { mutableStateOf(snapshotLines()) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(version, view) {
        lines = snapshotLines()
        if (autoScroll && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Логи") },
                navigationIcon = { IconButton(onClick = { haptic.performUiHaptic(UiHapticAction.NAVIGATION); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    TextButton(onClick = { haptic.performUiHaptic(UiHapticAction.LOG_PLAY_PAUSE); autoScroll = !autoScroll }) {
                        Text(if (autoScroll) "Авто: вкл" else "Авто: выкл")
                    }
                    IconButton(onClick = {
                        haptic.performUiHaptic(UiHapticAction.LOG_COPY)
                        clipboard.setText(AnnotatedString(snapshotText()))
                        Toast.makeText(context, "Лог скопирован", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Скопировать весь лог") }
                    IconButton(onClick = {
                        haptic.performUiHaptic(UiHapticAction.LOG_SAVE)
                        scope.launch {
                            val ok = saveDebugLog(context, snapshotText(), sessionLog = view == DebugLogView.SESSION)
                            Toast.makeText(
                                context,
                                if (ok) "Лог сохранён в Загрузки" else "Лог пуст или сохранить не удалось",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) { Icon(Icons.Default.Download, "Скачать .txt") }
                    IconButton(onClick = {
                        haptic.performUiHaptic(UiHapticAction.LOG_CLEAR)
                        if (view == DebugLogView.RECORDING) logger.clear() else logger.clearSessionLogs()
                    }) { Icon(Icons.Default.DeleteSweep, "Очистить") }
                }
            )
        }
    ) { padding ->
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (view == DebugLogView.RECORDING)
                        "Лог записи пуст. Включите отладку и нажмите Play."
                    else
                        "Лог сессии пуст. Здесь хранятся важные события последних 10 минут работы приложения.",
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF0F172A)),
                state = listState
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        color = Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticsScreen(statistics: ScannerStatisticsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val version by statistics.version.collectAsState()
    var viewMode by remember { mutableStateOf(StatisticsViewMode.SHORT) }
    var confirmReset by remember { mutableStateOf(false) }
    val representation = remember(version, viewMode) { statistics.format(viewMode) }
    val fullTechnicalReport = remember(version) { statistics.format(StatisticsViewMode.FULL) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        haptic.performUiHaptic(UiHapticAction.STAT_COPY)
                        clipboard.setText(AnnotatedString(fullTechnicalReport))
                        Toast.makeText(context, "Полный технический отчёт скопирован", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Скопировать") }
                    IconButton(onClick = {
                        haptic.performUiHaptic(UiHapticAction.STAT_SAVE)
                        scope.launch {
                            val ok = saveStatistics(context, fullTechnicalReport)
                            Toast.makeText(
                                context,
                                if (ok) "Статистика сохранена в Загрузки" else "Сохранить статистику не удалось",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) { Icon(Icons.Default.Download, "Скачать полный технический отчёт .txt") }
                    IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Default.DeleteSweep, "Сбросить")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatisticsModeButton(
                    title = "Кратко",
                    selected = viewMode == StatisticsViewMode.SHORT,
                    onClick = { haptic.performUiHaptic(UiHapticAction.STAT_VIEW_MODE); viewMode = StatisticsViewMode.SHORT },
                    modifier = Modifier.weight(1f)
                )
                StatisticsModeButton(
                    title = "Полная",
                    selected = viewMode == StatisticsViewMode.FULL,
                    onClick = { haptic.performUiHaptic(UiHapticAction.STAT_VIEW_MODE); viewMode = StatisticsViewMode.FULL },
                    modifier = Modifier.weight(1f)
                )
            }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
                    item {
                        if (viewMode == StatisticsViewMode.SHORT) {
                            ReadableShortStatistics(representation)
                        } else {
                            Text(
                                representation,
                                color = Color(0xFFE2E8F0),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.fillMaxWidth().padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить статистику?") },
            text = { Text("Будут удалены все накопленные счётчики и распределения.") },
            confirmButton = {
                TextButton(onClick = {
                    statistics.reset()
                    confirmReset = false
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ReadableShortStatistics(text: String) {
    val sections = remember(text) { parseStatisticsSections(text) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        sections.forEach { (title, lines) ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(title, color = Color(0xFF93C5FD), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    lines.forEach { line ->
                        val note = line.startsWith("ℹ")
                        Text(
                            line,
                            color = if (note) Color(0xFF94A3B8) else Color(0xFFE2E8F0),
                            fontSize = if (note) 12.sp else 14.sp,
                            lineHeight = if (note) 16.sp else 19.sp
                        )
                    }
                }
            }
        }
    }
}

private fun parseStatisticsSections(text: String): List<Pair<String, List<String>>> {
    val result = mutableListOf<Pair<String, List<String>>>()
    var title = "Отчёт"
    var lines = mutableListOf<String>()
    fun flush() {
        val content = lines.filter(String::isNotBlank)
        if (content.isNotEmpty()) result += title to content
        lines = mutableListOf()
    }
    text.lineSequence().forEach { raw ->
        if (raw.startsWith("## ")) {
            flush()
            title = raw.removePrefix("## ").trim()
        } else {
            lines += raw
        }
    }
    flush()
    return result.ifEmpty { listOf("Отчёт" to listOf("Данных пока нет.")) }
}

@Composable
private fun StatisticsModeButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color(0xFFDDEAFE) else Color(0xFFE2E8F0),
        border = if (selected) BorderStroke(1.dp, Color(0xFF60A5FA)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(title, color = if (selected) Color(0xFF1D4ED8) else Color(0xFF475569))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val candidates by vm.recoveryCandidates.collectAsState()
    val busy by vm.recoveryBusy.collectAsState()
    val message by vm.recoveryMessage.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::recoverPhoto)
    }
    LaunchedEffect(Unit) { vm.refreshRecovery() }
    androidx.activity.compose.BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Восстановление из фото") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Button(
                onClick = { picker.launch("image/*") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(54.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Восстанавливаю…")
                } else {
                    Icon(Icons.Default.CameraAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать фотографию")
                }
            }
            if (candidates.isEmpty() && !busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Здесь появятся найденные Data Matrix\nдо добавления в активный список", color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(candidates, key = { it.id }) { candidate ->
                        RecoveryCandidateRow(
                            candidate = candidate,
                            onAccept = { vm.acceptRecovery(candidate.id) },
                            onDelete = { vm.deleteRecovery(candidate.id) }
                        )
                    }
                }
            }
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = vm::clearRecoveryMessage,
            title = { Text("Восстановление") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::clearRecoveryMessage) { Text("ОК") } }
        )
    }
}

@Composable
private fun RecoveryCandidateRow(
    candidate: RecoveryCandidate,
    onAccept: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).background(Color.White).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DataMatrixImage(candidate.rawBytes, candidate.isGs1, 80.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(candidate.displayText, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(formatTime(candidate.detectedAt), fontSize = 12.sp, color = Color.DarkGray)
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onAccept) { Icon(Icons.Default.Check, null); Text("Принять") }
            TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Text("Удалить") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ViewerScreen(vm: AppViewModel, initial: ViewerState, onBack: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var viewerIds by remember(initial.ids) { mutableStateOf(initial.ids) }
    val initialPage = initial.index.coerceIn(0, initial.ids.lastIndex.coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { viewerIds.size })
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<Map<Long, CodeRecord>>(emptyMap()) }
    var scanFrame by remember { mutableStateOf<StoredScanFrame?>(null) }
    var showPhoto by remember { mutableStateOf(false) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    val matrixSize by vm.matrixSize.collectAsState()
    val displayedPage = pagerState.currentPage.coerceAtMost(viewerIds.lastIndex.coerceAtLeast(0))
    val currentId = viewerIds.getOrNull(displayedPage)
    val current = currentId?.let(records::get)

    LaunchedEffect(initial.ids) { vm.records(initial.ids) { records = it } }
    LaunchedEffect(viewerIds, pendingPage) {
        val target = pendingPage ?: return@LaunchedEffect
        if (viewerIds.isNotEmpty()) pagerState.scrollToPage(target.coerceIn(viewerIds.indices))
        pendingPage = null
    }
    LaunchedEffect(currentId) {
        scanFrame = null
        showPhoto = false
        currentId?.let { vm.scanFrame(it) { value -> scanFrame = value } }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)

    fun animateTo(page: Int) {
        if (page in viewerIds.indices) scope.launch { pagerState.animateScrollToPage(page) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${displayedPage + 1} / ${viewerIds.size}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (!showPhoto) IconButton(onClick = vm::cycleMatrixSize) { MatrixSizeIcon(matrixSize) }
                    if (showPhoto) {
                        scanFrame?.let { frame ->
                            TextButton(onClick = {
                                scope.launch {
                                    val saved = saveJpegToGallery(context, frame.jpeg, "DataMatrix_source")
                                    Toast.makeText(context, if (saved) "Исходное фото сохранено" else "Не удалось сохранить фото", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Download, null)
                                Text("Скачать")
                            }
                        }
                    }
                    if (scanFrame != null) {
                        IconButton(onClick = {
                            showPhoto = !showPhoto
                            vm.logUiEvent("viewer_toggle_photo", "photo" to if (showPhoto) 1 else 0)
                        }) {
                            Icon(if (showPhoto) Icons.Default.QrCode2 else Icons.Default.PhotoCamera, if (showPhoto) "Показать Data Matrix" else "Показать кадр")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    current?.let { record ->
                        performStrongActionHaptic(context)
                        if (!record.isScanned) {
                            vm.setScanned(record.id, true) { }
                        }
                        if (initial.source != RecordStatus.ARCHIVED) {
                            val remaining = viewerIds.filterNot { it == record.id }
                            if (remaining.isEmpty()) {
                                onBack()
                            } else {
                                records = records - record.id
                                pendingPage = nextViewerIndexAfterRemoval(displayedPage, remaining.size)
                                viewerIds = remaining
                            }
                        } else {
                            animateTo(displayedPage + 1)
                        }
                    }
                },
                modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp).height(180.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("ОТСКАНИРОВАНО", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            beyondViewportPageCount = 1,
            pageSpacing = 8.dp
        ) { page ->
            val pageRecord = viewerIds.getOrNull(page)?.let(records::get)
            if (pageRecord == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Загрузка…") }
            } else {
                ViewerCard(
                    record = pageRecord,
                    frame = scanFrame.takeIf { page == pagerState.currentPage },
                    showPhoto = showPhoto && page == pagerState.currentPage,
                    matrixSize = matrixSize,
                    onPrevious = { animateTo(page - 1) },
                    onNext = { animateTo(page + 1) }
                )
            }
        }
    }
}

@Composable
private fun ViewerCard(
    record: CodeRecord,
    frame: StoredScanFrame?,
    showPhoto: Boolean,
    matrixSize: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(record.id) {
                detectTapGestures { offset -> if (offset.x < size.width / 2f) onPrevious() else onNext() }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showPhoto && frame != null) {
            StoredFrameImage(frame, Modifier.fillMaxWidth().weight(1f).padding(12.dp))
        } else {
            val size = listOf(95.dp, 190.dp, 340.dp)[matrixSize]
            DataMatrixImage(record.rawBytes, record.isGs1, size)
        }
        Spacer(Modifier.height(24.dp))
        StatusTag(duplicate = record.isDuplicate)
        Text(if (record.isScanned) "Отсканировано" else "Не отсканировано", fontSize = 17.sp)
        Text(record.displayText, Modifier.padding(16.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MatrixSizeIcon(level: Int) {
    Canvas(Modifier.size(28.dp)) {
        drawRect(Color.DarkGray, style = Stroke(2.2f))
        val fraction = listOf(.3f, .55f, .82f)[level]
        val side = size.minDimension * fraction
        drawRect(
            color = Color.DarkGray,
            topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
            size = androidx.compose.ui.geometry.Size(side, side),
            style = Stroke(2.2f)
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id
private fun RecordStatus.title() = when (this) {
    RecordStatus.ACTIVE -> "Активные"
    RecordStatus.ARCHIVED -> "Архив"
    RecordStatus.TRASH -> "Корзина"
}

private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
private fun formatTime(timestamp: Long): String = synchronized(timeFormat) { timeFormat.format(Date(timestamp)) }
