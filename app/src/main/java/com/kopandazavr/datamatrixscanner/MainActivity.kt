package com.kopandazavr.datamatrixscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kopandazavr.datamatrixscanner.data.CodeRecord
import com.kopandazavr.datamatrixscanner.data.RecordStatus
import com.kopandazavr.datamatrixscanner.data.ScanEvent
import com.kopandazavr.datamatrixscanner.scanner.DataMatrixAnalyzer
import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import com.kopandazavr.datamatrixscanner.ui.DataMatrixImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

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

private enum class AppMode { LIST, CAMERA, VIEWER }
private data class ViewerState(val ids: List<Long>, val index: Int)

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
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    val analyzer = remember { DataMatrixAnalyzer(vm::onDecoded) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember(permissionGranted) {
        if (!permissionGranted) null else LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
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
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    analyzer.fullScreen = mode == AppMode.CAMERA

    when (mode) {
        AppMode.LIST -> ListScreen(
            vm = vm,
            controller = controller,
            permissionGranted = permissionGranted,
            onCamera = { mode = AppMode.CAMERA },
            onOpenViewer = { id, ids ->
                viewer = ViewerState(ids, ids.indexOf(id).coerceAtLeast(0))
                mode = AppMode.VIEWER
            }
        )
        AppMode.CAMERA -> FullCameraScreen(
            vm = vm,
            controller = controller,
            permissionGranted = permissionGranted,
            onBack = { mode = AppMode.LIST }
        )
        AppMode.VIEWER -> ViewerScreen(
            vm = vm,
            initial = requireNotNull(viewer),
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
    onCamera: () -> Unit,
    onOpenViewer: (Long, List<Long>) -> Unit
) {
    val section by vm.section.collectAsState()
    val records by vm.records.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(RangeSelectionState()) }
    var eventRecord by remember { mutableStateOf<CodeRecord?>(null) }
    var events by remember { mutableStateOf<List<ScanEvent>>(emptyList()) }

    LaunchedEffect(section) { selection = RangeSelectionState() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(section.title()) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF8FAFC)),
                    actions = {
                        IconButton(onClick = onCamera) { Icon(Icons.Default.CameraAlt, "Камера") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Раздел") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                RecordStatus.entries.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item.title()) },
                                        onClick = { menu = false; vm.setSection(item) }
                                    )
                                }
                            }
                        }
                    }
                )
                CameraPreview(controller, permissionGranted, Modifier.fillMaxWidth().height(116.dp))
            }
        },
        bottomBar = {
            if (selection.isActive) {
                SelectionBar(section, selection.selected, onMove = { target ->
                    vm.move(selection.selected, target)
                    selection = RangeSelectionState()
                })
            }
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(if (section == RecordStatus.ACTIVE) "Направьте камеру на Data Matrix" else "Здесь пока пусто", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(records, key = { it.id }) { record ->
                    RecordRow(
                        record = record,
                        selected = record.id in selection.selected,
                        selectionMode = selection.isActive,
                        onLongClick = {
                            selection = selection.selectRange(record.id, records.map(CodeRecord::id))
                        },
                        onClick = {
                            selection = selection.toggle(record.id, records.map(CodeRecord::id))
                        },
                        onMatrix = { onOpenViewer(record.id, records.map(CodeRecord::id)) },
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
}

@Composable
private fun CameraPreview(controller: LifecycleCameraController?, permissionGranted: Boolean, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (controller != null) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; this.controller = controller } },
                update = { it.controller = controller },
                modifier = Modifier.fillMaxSize()
            )
        } else Text(if (permissionGranted) "Камера недоступна" else "Нужно разрешение камеры", color = Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(
    record: CodeRecord,
    selected: Boolean,
    selectionMode: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onMatrix: () -> Unit,
    onStatus: () -> Unit,
    onScanned: (Boolean) -> Unit
) {
    val platformConfiguration = LocalViewConfiguration.current
    val oneSecondLongPress = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            override val longPressTimeoutMillis: Long = 1_000L
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides oneSecondLongPress) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(if (selected) Color(0xFFDDEAFE) else Color.White)
                .combinedClickable(
                    onClick = { if (selectionMode) onClick() },
                    onLongClick = onLongClick
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.combinedClickable(
                    onClick = { if (selectionMode) onClick() else onMatrix() },
                    onLongClick = onLongClick
                )
            ) {
                DataMatrixImage(record.rawBytes, record.isGs1, 80.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(record.displayText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTime(record.lastScanAt), fontSize = 13.sp, color = Color.DarkGray)
                TextButton(
                    onClick = { if (selectionMode) onClick() else onStatus() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(if (record.isDuplicate) "Дубликат ×${record.duplicateCount}" else "Уникальный", fontSize = 13.sp)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(
                    checked = if (selectionMode) selected else record.status == RecordStatus.ARCHIVED,
                    onCheckedChange = { if (selectionMode) onClick() else onScanned(it) }
                )
                Text(if (record.status == RecordStatus.ARCHIVED) "В архиве" else "В архив", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SelectionBar(section: RecordStatus, selected: Set<Long>, onMove: (RecordStatus) -> Unit) {
    BottomAppBar(containerColor = Color(0xFFEFF6FF)) {
        Text("Выбрано: ${selected.size}", Modifier.padding(horizontal = 12.dp).weight(1f), fontWeight = FontWeight.Bold)
        when (section) {
            RecordStatus.ACTIVE -> {
                TextButton(onClick = { onMove(RecordStatus.ARCHIVED) }) { Icon(Icons.Default.Archive, null); Text("В архив") }
                TextButton(onClick = { onMove(RecordStatus.TRASH) }) { Icon(Icons.Default.Delete, null); Text("Удалить") }
            }
            RecordStatus.ARCHIVED -> {
                TextButton(onClick = { onMove(RecordStatus.ACTIVE) }) { Icon(Icons.Default.Restore, null); Text("Вернуть") }
                TextButton(onClick = { onMove(RecordStatus.TRASH) }) { Icon(Icons.Default.Delete, null); Text("В корзину") }
            }
            RecordStatus.TRASH -> {
                TextButton(onClick = { onMove(RecordStatus.ACTIVE) }) { Icon(Icons.Default.Restore, null); Text("Вернуть") }
                TextButton(onClick = { onMove(RecordStatus.ARCHIVED) }) { Icon(Icons.Default.Archive, null); Text("В архив") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullCameraScreen(
    vm: AppViewModel,
    controller: LifecycleCameraController?,
    permissionGranted: Boolean,
    onBack: () -> Unit
) {
    val count by vm.setCount.collectAsState()
    val boxes by vm.boxes.collectAsState()
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(controller, permissionGranted, Modifier.fillMaxSize())
        DetectionOverlay(boxes)
        Row(
            Modifier.fillMaxWidth().padding(top = 28.dp, start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Text("Распознано: $count", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = vm::nextBatch,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().padding(16.dp).height(64.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Следующий набор", fontSize = 20.sp) }
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
            if (canvasAspect > box.imageAspect) {
                contentHeight = size.height
                contentWidth = contentHeight * box.imageAspect
                left = (size.width - contentWidth) / 2f
                top = 0f
            } else {
                contentWidth = size.width
                contentHeight = contentWidth / box.imageAspect
                left = 0f
                top = (size.height - contentHeight) / 2f
            }
            val path = Path()
            box.points.forEachIndexed { index, point ->
                val x = left + point.x * contentWidth
                val y = top + point.y * contentHeight
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, Color(0xFF22C55E), style = Stroke(width = 6f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ViewerScreen(vm: AppViewModel, initial: ViewerState, onBack: () -> Unit) {
    var index by remember { mutableStateOf(initial.index.coerceIn(0, initial.ids.lastIndex.coerceAtLeast(0))) }
    var record by remember { mutableStateOf<CodeRecord?>(null) }
    val matrixSize by vm.matrixSize.collectAsState()
    var drag by remember { mutableFloatStateOf(0f) }
    val id = initial.ids.getOrNull(index)
    LaunchedEffect(id) { id?.let { vm.record(it) { value -> record = value } } }
    androidx.activity.compose.BackHandler(onBack = onBack)

    fun previous() { if (index > 0) index -= 1 }
    fun next() { if (index < initial.ids.lastIndex) index += 1 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${index + 1} / ${initial.ids.size}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = vm::cycleMatrixSize) { MatrixSizeIcon(matrixSize) }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    record?.let { current ->
                        if (!current.isScanned) vm.setScanned(current.id, true) { }
                        next()
                    }
                },
                modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp).height(72.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("ОТСКАНИРОВАНО", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    ) { padding ->
        val current = record
        Column(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (current == null) Text("Загрузка…") else {
                val size = listOf(190.dp, 270.dp, 340.dp)[matrixSize]
                Box(
                    Modifier
                        .size(size)
                        .pointerInput(index) {
                            detectHorizontalDragGestures(
                                onDragStart = { drag = 0f },
                                onHorizontalDrag = { _, amount -> drag += amount },
                                onDragEnd = {
                                    if (abs(drag) > 60f) if (drag < 0) next() else previous()
                                    drag = 0f
                                }
                            )
                        }
                ) {
                    DataMatrixImage(current.rawBytes, current.isGs1, size)
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().combinedClickable(onClick = { previous() }, onLongClick = {}))
                        Box(Modifier.weight(1f).fillMaxHeight().combinedClickable(onClick = { next() }, onLongClick = {}))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(if (current.isDuplicate) "Дубликат" else "Уникальный", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(if (current.isScanned) "Отсканировано" else "Не отсканировано", fontSize = 17.sp)
                Text(current.displayText, Modifier.padding(16.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
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
