package com.kopandazavr.datamatrixscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kopandazavr.datamatrixscanner.data.CodeRecord
import com.kopandazavr.datamatrixscanner.data.RecordStatus
import com.kopandazavr.datamatrixscanner.data.RecoveryCandidate
import com.kopandazavr.datamatrixscanner.data.ScanEvent
import com.kopandazavr.datamatrixscanner.data.StoredScanFrame
import com.kopandazavr.datamatrixscanner.scanner.DataMatrixAnalyzer
import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import com.kopandazavr.datamatrixscanner.scanner.DetectionHighlight
import com.kopandazavr.datamatrixscanner.scanner.ScanEnhancementMode
import com.kopandazavr.datamatrixscanner.ui.DataMatrixImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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

private enum class AppMode { LIST, VIEWER, RECOVERY }
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
    var cameraFullscreen by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    val analyzer = remember { DataMatrixAnalyzer(vm::onDecoded, vm::onPotentialBoxes) }
    val enhancementMode by vm.scanEnhancementMode.collectAsState()
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember(permissionGranted) {
        if (!permissionGranted) null else LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisTargetSize(CameraController.OutputSize(Size(1280, 720)))
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE)
            setImageAnalysisAnalyzer(executor, analyzer)
            bindToLifecycle(lifecycleOwner)
        }
    }
    DisposableEffect(controller) {
        onDispose {
            controller?.clearImageAnalysisAnalyzer()
            controller?.unbind()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            analyzer.close()
            executor.shutdown()
        }
    }
    analyzer.fullScreen = mode == AppMode.LIST && cameraFullscreen
    analyzer.enhancementMode = enhancementMode
    LaunchedEffect(mode, controller) {
        if (mode == AppMode.LIST) {
            controller?.setImageAnalysisAnalyzer(executor, analyzer)
        } else {
            controller?.clearImageAnalysisAnalyzer()
        }
    }
    val focusController = rememberCameraFocusController(
        active = mode == AppMode.LIST,
        controller = controller,
        analyzer = analyzer
    )

    when (mode) {
        AppMode.LIST -> ListScreen(
            vm = vm,
            controller = controller,
            permissionGranted = permissionGranted,
            fullscreen = cameraFullscreen,
            enhancementMode = enhancementMode,
            focusController = focusController,
            onToggleFullscreen = { cameraFullscreen = !cameraFullscreen },
            onRecovery = {
                cameraFullscreen = false
                mode = AppMode.RECOVERY
            },
            onTargetedRescue = analyzer::requestTargetedRescue,
            onEnhancementMode = vm::setScanEnhancementMode,
            onOpenViewer = { id, ids, source ->
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(
    vm: AppViewModel,
    controller: LifecycleCameraController?,
    permissionGranted: Boolean,
    fullscreen: Boolean,
    enhancementMode: ScanEnhancementMode,
    focusController: CameraFocusController,
    onToggleFullscreen: () -> Unit,
    onRecovery: () -> Unit,
    onTargetedRescue: () -> Unit,
    onEnhancementMode: (ScanEnhancementMode) -> Unit,
    onOpenViewer: (Long, List<Long>, RecordStatus) -> Unit
) {
    val section by vm.section.collectAsState()
    val records by vm.records.collectAsState()
    val boxes by vm.boxes.collectAsState()
    val count by vm.setCount.collectAsState()
    val currentBatchId by vm.batchId.collectAsState()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var enhancementDialog by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(RangeSelectionState()) }
    var eventRecord by remember { mutableStateOf<CodeRecord?>(null) }
    var events by remember { mutableStateOf<List<ScanEvent>>(emptyList()) }
    var topBarHeightPx by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(section) { selection = RangeSelectionState() }
    LaunchedEffect(section, records.firstOrNull()?.id, records.firstOrNull()?.lastScanAt) {
        if (section == RecordStatus.ACTIVE && records.isNotEmpty()) listState.animateScrollToItem(0)
    }
    androidx.activity.compose.BackHandler(enabled = fullscreen, onBack = onToggleFullscreen)

    Box(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            modifier = Modifier.onSizeChanged { topBarHeightPx = it.height },
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
                                            text = { Text("Восстановить из фото") },
                                            onClick = { menu = false; onRecovery() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Усиление: ${enhancementMode.title}") },
                                            onClick = { menu = false; enhancementDialog = true }
                                        )
                                    }
                                }
                            }
                        )
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
                                onMatrix = { onOpenViewer(record.id, records.map(CodeRecord::id), section) },
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
            controller = controller,
            permissionGranted = permissionGranted,
            boxes = boxes,
            modifier = cameraModifier,
            fullscreen = fullscreen,
            onClick = onToggleFullscreen,
            onBack = onToggleFullscreen,
            onPhoto = {
                val activeController = controller
                if (activeController == null) {
                    Toast.makeText(context, "Камера недоступна", Toast.LENGTH_SHORT).show()
                } else {
                    saveCameraPhoto(context, activeController) { result ->
                        Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onTargetedRescue = onTargetedRescue,
            focusController = focusController,
            recognizedCount = count,
            onNextBatch = vm::nextBatch
        )
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

    if (enhancementDialog) {
        EnhancementModeDialog(
            selected = enhancementMode,
            onSelect = {
                onEnhancementMode(it)
                enhancementDialog = false
            },
            onDismiss = { enhancementDialog = false }
        )
    }
}

@Composable
private fun EnhancementModeDialog(
    selected: ScanEnhancementMode,
    onSelect: (ScanEnhancementMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Усиление распознавания") },
        text = {
            Column {
                Text("Оба движка сканируют весь видимый кадр. Кнопка со спидометром запускает усиленный проход; её можно удерживать для повторных проходов.")
                Spacer(Modifier.height(10.dp))
                ScanEnhancementMode.entries.forEach { mode ->
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
                            Text(mode.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            if (mode != ScanEnhancementMode.OFF) {
                                Text("${mode.decoderAttemptCount} попыток", color = Color.Gray)
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
    controller: LifecycleCameraController?,
    permissionGranted: Boolean,
    boxes: List<DetectionBox>,
    modifier: Modifier,
    fullscreen: Boolean,
    onClick: () -> Unit,
    onBack: () -> Unit,
    onPhoto: (() -> Unit)? = null,
    onTargetedRescue: (() -> Unit)? = null,
    focusController: CameraFocusController? = null,
    recognizedCount: Int? = null,
    onNextBatch: (() -> Unit)? = null
) {
    var enhancementActive by remember { mutableStateOf(false) }
    val cameraFrameModifier = modifier
        .clipToBounds()
        .background(Color.Black)
        .then(
            if (enhancementActive) Modifier.border(3.dp, Color(0xFF3B82F6)) else Modifier
        )
    BoxWithConstraints(cameraFrameModifier, contentAlignment = Alignment.Center) {
        if (controller != null) {
            // Keep the camera surface at one stable measured size. Resizing PreviewView
            // makes CameraX renegotiate the surface and produces a visible black flash.
            // Fullscreen is therefore only a render transform; the surrounding box clips
            // the enlarged preview exactly like FILL_CENTER would at the fullscreen ratio.
            val fullscreenScale = if (fullscreen) {
                (maxHeight.value / EmbeddedCameraPreviewHeight.value).coerceAtLeast(1f)
            } else {
                1f
            }
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                } },
                update = { it.controller = controller },
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .requiredHeight(EmbeddedCameraPreviewHeight)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        scaleX = fullscreenScale
                        scaleY = fullscreenScale
                    }
            )
        } else Text(if (permissionGranted) "Камера недоступна" else "Нужно разрешение камеры", color = Color.White)
        DetectionOverlay(boxes)
        CameraAimOverlay(active = enhancementActive)
        Box(Modifier.fillMaxSize().clickable(onClick = onClick))
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
                ) { Text("Следующий набор", fontSize = 20.sp) }
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                onTargetedRescue?.let { action ->
                    EnhancementHoldButton(
                        fullscreen = true,
                        onPulse = action,
                        onActiveChange = { enhancementActive = it }
                    )
                }
                onPhoto?.let { action -> FullscreenCameraButton(action, Icons.Default.CameraAlt, "Сделать фото") }
                focusController?.let { focus ->
                    FocusModeButton(
                        fullscreen = true,
                        autoEnabled = focus.autoEnabled,
                        busy = focus.busy,
                        onTap = focus.onTap,
                        onLongPress = focus.onLongPress
                    )
                }
            }
        } else if (recognizedCount != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
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
        if (!fullscreen && onNextBatch != null) {
            Surface(
                onClick = onNextBatch,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.46f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .56f))
            ) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text("Следующий набор", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (!fullscreen && (onTargetedRescue != null || onPhoto != null || focusController != null)) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                onTargetedRescue?.let { action ->
                    EnhancementHoldButton(
                        fullscreen = false,
                        onPulse = action,
                        onActiveChange = { enhancementActive = it }
                    )
                }
                onPhoto?.let { action -> CameraOverlayButton(action, Icons.Default.CameraAlt, "Сделать фото") }
                focusController?.let { focus ->
                    FocusModeButton(
                        fullscreen = false,
                        autoEnabled = focus.autoEnabled,
                        busy = focus.busy,
                        onTap = focus.onTap,
                        onLongPress = focus.onLongPress
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraOverlayButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .56f))
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(29.dp))
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
    val platformConfiguration = LocalViewConfiguration.current
    val halfSecondLongPress = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            override val longPressTimeoutMillis: Long = 500L
        }
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
                        onClick = { if (selectionMode) onClick() },
                        onLongClick = onLongClick
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.combinedClickable(
                        onClick = { if (selectionMode) onClick() else onMatrix() },
                        onLongClick = onLongClick
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
                        onClick = { if (selectionMode) onClick() else onStatus() }
                    )
                }
                Button(
                    onClick = { if (selectionMode) onClick() else onScanned(true) },
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
private fun FullscreenCameraButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(62.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color.Black.copy(alpha = 0.42f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun DetectionOverlay(boxes: List<DetectionBox>) {
    Canvas(Modifier.fillMaxSize()) {
        boxes.forEach { box ->
            val canvasAspect = size.width / size.height.coerceAtLeast(1f)
            val contentWidth: Float
            val contentHeight: Float
            val left: Float
            val top: Float
            // Same FILL_CENTER transform as PreviewView. Usually cropRect already has
            // the exact canvas aspect, but this also handles a transient resize safely.
            if (canvasAspect > box.imageAspect) {
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
            val path = Path()
            box.points.forEachIndexed { index, point ->
                val x = left + point.x * contentWidth
                val y = top + point.y * contentHeight
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val (color, strokeWidth) = when (box.highlight) {
                DetectionHighlight.POTENTIAL -> Color.White to 3.5f
                DetectionHighlight.ACTIVE -> Color(0xFF22C55E) to 6f
                DetectionHighlight.DUPLICATE -> Color(0xFFFACC15) to 6f
            }
            drawPath(path, color, style = Stroke(width = strokeWidth))
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
                    if (scanFrame != null) {
                        IconButton(onClick = { showPhoto = !showPhoto }) {
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
                modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp).height(72.dp),
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
