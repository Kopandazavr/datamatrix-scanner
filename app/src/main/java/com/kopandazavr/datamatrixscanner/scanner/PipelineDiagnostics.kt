package com.kopandazavr.datamatrixscanner.scanner

data class PipelineDiagnostics(
    val fastJobs: Int = 0,
    val mlPending: Int = 0,
    val mlInFlight: Int = 0,
    val heavyPending: Int = 0,
    val heavyInFlight: Int = 0,
    val oldestFrameAgeMs: Long = 0L,
    val currentFrameId: Long = 0L,
    val updatedAtElapsedMs: Long = 0L
) {
    val totalJobs: Int get() = fastJobs + mlPending + mlInFlight + heavyPending + heavyInFlight
}
