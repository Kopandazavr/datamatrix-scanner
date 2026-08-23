package com.kopandazavr.datamatrixscanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kopandazavr.datamatrixscanner.data.CodeRecord
import com.kopandazavr.datamatrixscanner.data.CodeRepository
import com.kopandazavr.datamatrixscanner.data.RecordStatus
import com.kopandazavr.datamatrixscanner.data.ScanEvent
import com.kopandazavr.datamatrixscanner.data.ScanOutcome
import com.kopandazavr.datamatrixscanner.scanner.DecodedDataMatrix
import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CodeRepository(application)
    private val prefs = application.getSharedPreferences("scanner_preferences", 0)
    private val scanMutex = Mutex()

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

    private var batchId = prefs.getLong("batch_id", 1L)
    private var boxGeneration = 0L

    init { refresh() }

    fun setSection(value: RecordStatus) {
        _section.value = value
        refresh()
    }

    fun onDecoded(items: List<DecodedDataMatrix>) {
        viewModelScope.launch(Dispatchers.IO) {
            scanMutex.withLock {
                val newBoxes = mutableListOf<DetectionBox>()
                var changed = false
                items.forEach { item ->
                    when (repository.scan(
                        rawBytes = item.rawBytes,
                        isGs1 = item.isGs1,
                        symbologyIdentifier = item.symbologyIdentifier,
                        contentType = item.contentType,
                        fallbackText = item.text,
                        batchId = batchId
                    )) {
                        is ScanOutcome.New -> {
                            _setCount.value += 1
                            newBoxes += item.box
                            changed = true
                        }
                        is ScanOutcome.Restored -> changed = true
                        ScanOutcome.IgnoredActive -> Unit
                    }
                }
                if (changed) _records.value = repository.list(_section.value)
                if (newBoxes.isNotEmpty()) showBoxes(newBoxes)
            }
        }
    }

    private fun showBoxes(newBoxes: List<DetectionBox>) {
        val generation = ++boxGeneration
        _boxes.value = newBoxes
        viewModelScope.launch {
            delay(850)
            if (generation == boxGeneration) _boxes.value = emptyList()
        }
    }

    fun nextBatch() {
        batchId += 1
        prefs.edit().putLong("batch_id", batchId).apply()
        _setCount.value = 0
        _boxes.value = emptyList()
    }

    fun setScanned(id: Long, scanned: Boolean, after: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setScanned(id, scanned)
            _records.value = repository.list(_section.value)
            after?.invoke()
        }
    }

    fun move(ids: Set<Long>, target: RecordStatus) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.move(ids, target)
            _records.value = repository.list(_section.value)
        }
    }

    fun events(id: Long, callback: (List<ScanEvent>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { callback(repository.events(id)) }
    }

    fun record(id: Long, callback: (CodeRecord?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { callback(repository.get(id)) }
    }

    fun cycleMatrixSize() {
        val next = (_matrixSize.value + 1) % 3
        _matrixSize.value = next
        prefs.edit().putInt("matrix_size", next).apply()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { _records.value = repository.list(_section.value) }
    }
}
