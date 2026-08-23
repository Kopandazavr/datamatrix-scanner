package com.kopandazavr.datamatrixscanner

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kopandazavr.datamatrixscanner.data.CodeRecord
import com.kopandazavr.datamatrixscanner.data.CodeRepository
import com.kopandazavr.datamatrixscanner.data.RecordStatus
import com.kopandazavr.datamatrixscanner.data.RecoveryCandidate
import com.kopandazavr.datamatrixscanner.data.ScanEvent
import com.kopandazavr.datamatrixscanner.data.ScanOutcome
import com.kopandazavr.datamatrixscanner.data.StoredScanFrame
import com.kopandazavr.datamatrixscanner.scanner.DecodedDataMatrix
import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import com.kopandazavr.datamatrixscanner.scanner.DetectionHighlight
import com.kopandazavr.datamatrixscanner.scanner.PhotoRecoveryDecoder
import com.kopandazavr.datamatrixscanner.scanner.ScanEnhancementMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CodeRepository(application)
    private val prefs = application.getSharedPreferences("scanner_preferences", 0)
    private val scanMutex = Mutex()
    private val photoDecoder = PhotoRecoveryDecoder()
    private val decodedFrames = MutableSharedFlow<List<DecodedDataMatrix>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val detectionCache = ConcurrentHashMap<String, DetectionHighlight>()

    private val _section = MutableStateFlow(RecordStatus.ACTIVE)
    val section: StateFlow<RecordStatus> = _section.asStateFlow()
    private val _records = MutableStateFlow<List<CodeRecord>>(emptyList())
    val records: StateFlow<List<CodeRecord>> = _records.asStateFlow()
    private val _setCount = MutableStateFlow(0)
    val setCount: StateFlow<Int> = _setCount.asStateFlow()
    private val _boxes = MutableStateFlow<List<DetectionBox>>(emptyList())
    val boxes: StateFlow<List<DetectionBox>> = _boxes.asStateFlow()
    private val _matrixSize = MutableStateFlow(prefs.getInt("matrix_size", 1).coerceIn(0, 2))
    val matrixSize: StateFlow<Int> = _matrixSize.asStateFlow()
    private val _recoveryCandidates = MutableStateFlow<List<RecoveryCandidate>>(emptyList())
    val recoveryCandidates: StateFlow<List<RecoveryCandidate>> = _recoveryCandidates.asStateFlow()
    private val _recoveryBusy = MutableStateFlow(false)
    val recoveryBusy: StateFlow<Boolean> = _recoveryBusy.asStateFlow()
    private val _recoveryMessage = MutableStateFlow<String?>(null)
    val recoveryMessage: StateFlow<String?> = _recoveryMessage.asStateFlow()
    private val _scanEnhancementMode = MutableStateFlow(
        ScanEnhancementMode.fromPreference(prefs.getString("scan_enhancement_mode", null))
    )
    val scanEnhancementMode: StateFlow<ScanEnhancementMode> = _scanEnhancementMode.asStateFlow()
    private val _batchId = MutableStateFlow(prefs.getLong("batch_id", 1L))
    val batchId: StateFlow<Long> = _batchId.asStateFlow()
    private var boxGeneration = 0L

    init {
        refresh()
        refreshRecovery()
        viewModelScope.launch(Dispatchers.IO) {
            decodedFrames.collect(::processDecodedFrame)
        }
    }

    fun setSection(value: RecordStatus) {
        _section.value = value
        refresh()
    }

    fun onDecoded(items: List<DecodedDataMatrix>) {
        decodedFrames.tryEmit(items)
    }

    private suspend fun processDecodedFrame(items: List<DecodedDataMatrix>) {
        if (items.isEmpty()) {
            clearBoxesAfterSilence()
            return
        }
        scanMutex.withLock {
            val visibleBoxes = mutableListOf<DetectionBox>()
            var changed = false
            var activatedRecord = false
            items.forEach { item ->
                val cacheKey = Base64.encodeToString(item.rawBytes, Base64.NO_WRAP)
                val highlight = detectionCache[cacheKey] ?: run {
                    val outcome = repository.scan(
                        rawBytes = item.rawBytes,
                        isGs1 = item.isGs1,
                        symbologyIdentifier = item.symbologyIdentifier,
                        contentType = item.contentType,
                        fallbackText = item.text,
                        batchId = _batchId.value,
                        capturedFrame = item.capturedFrame,
                        detectionBox = item.box
                    )
                    val resolved = when (outcome) {
                        is ScanOutcome.New -> {
                            changed = true
                            activatedRecord = true
                            DetectionHighlight.ACTIVE
                        }
                        is ScanOutcome.Restored -> {
                            changed = true
                            activatedRecord = true
                            if (outcome.from == RecordStatus.ARCHIVED) DetectionHighlight.DUPLICATE else DetectionHighlight.ACTIVE
                        }
                        is ScanOutcome.IgnoredActive -> {
                            if (outcome.record.isDuplicate) DetectionHighlight.DUPLICATE else DetectionHighlight.ACTIVE
                        }
                    }
                    detectionCache[cacheKey] = resolved
                    resolved
                }
                visibleBoxes += item.box.copy(highlight = highlight)
            }
            if (changed) {
                if (activatedRecord) _section.value = RecordStatus.ACTIVE
                refreshRecordsAndCount()
            }
            showBoxes(visibleBoxes)
        }
    }

    private fun showBoxes(newBoxes: List<DetectionBox>) {
        val generation = ++boxGeneration
        _boxes.value = newBoxes
        viewModelScope.launch {
            delay(320)
            if (generation == boxGeneration) _boxes.value = emptyList()
        }
    }

    private fun clearBoxesAfterSilence() {
        val generation = boxGeneration
        viewModelScope.launch {
            delay(180)
            if (generation == boxGeneration) _boxes.value = emptyList()
        }
    }

    fun nextBatch() {
        _batchId.value += 1
        prefs.edit().putLong("batch_id", _batchId.value).apply()
        _setCount.value = 0
        _boxes.value = emptyList()
    }

    fun setScanEnhancementMode(mode: ScanEnhancementMode) {
        _scanEnhancementMode.value = mode
        prefs.edit().putString("scan_enhancement_mode", mode.name).apply()
    }

    fun setScanned(id: Long, scanned: Boolean, after: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setScanned(id, scanned)
            detectionCache.clear()
            refreshRecordsAndCount()
            after?.invoke()
        }
    }

    fun move(ids: Set<Long>, target: RecordStatus) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.move(ids, target)
            detectionCache.clear()
            refreshRecordsAndCount()
        }
    }

    fun recoverPhoto(uri: Uri) {
        if (_recoveryBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _recoveryBusy.value = true
            _recoveryMessage.value = null
            try {
                val decoded = photoDecoder.decode(getApplication<Application>().contentResolver, uri)
                val added = repository.addRecoveryCandidates(decoded)
                _recoveryCandidates.value = repository.recoveryCandidates()
                _recoveryMessage.value = when {
                    decoded.isEmpty() -> "Data Matrix на фотографии восстановить не удалось"
                    added == 0 -> "Все найденные коды уже есть в списке восстановления"
                    else -> "Восстановлено: $added"
                }
            } catch (_: Throwable) {
                _recoveryMessage.value = "Не удалось обработать фотографию"
            } finally {
                _recoveryBusy.value = false
            }
        }
    }

    fun acceptRecovery(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.acceptRecoveryCandidate(id, _batchId.value)
            detectionCache.clear()
            _recoveryCandidates.value = repository.recoveryCandidates()
            refreshRecordsAndCount()
        }
    }

    fun deleteRecovery(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteRecoveryCandidate(id)
            _recoveryCandidates.value = repository.recoveryCandidates()
        }
    }

    fun clearRecoveryMessage() { _recoveryMessage.value = null }

    fun refreshRecovery() {
        viewModelScope.launch(Dispatchers.IO) { _recoveryCandidates.value = repository.recoveryCandidates() }
    }

    fun events(id: Long, callback: (List<ScanEvent>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { callback(repository.events(id)) }
    }

    fun record(id: Long, callback: (CodeRecord?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { callback(repository.get(id)) }
    }

    fun records(ids: List<Long>, callback: (Map<Long, CodeRecord>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            callback(ids.mapNotNull { id -> repository.get(id)?.let { id to it } }.toMap())
        }
    }

    fun scanFrame(id: Long, callback: (StoredScanFrame?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { callback(repository.scanFrame(id)) }
    }

    fun cycleMatrixSize() {
        val next = (_matrixSize.value + 1) % 3
        _matrixSize.value = next
        prefs.edit().putInt("matrix_size", next).apply()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { refreshRecordsAndCount() }
    }

    private fun refreshRecordsAndCount() {
        _records.value = repository.list(_section.value)
        _setCount.value = repository.activeCount(_batchId.value)
    }
}
