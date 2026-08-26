package com.kopandazavr.datamatrixscanner

internal data class WorkerLevelResult(
    val workers: Int,
    val jobsPerSecond: Double,
    val p95Ms: Double,
    val errors: Int,
    val correct: Int,
    val attempted: Int
) {
    val valid: Boolean get() = errors == 0 && correct == attempted && attempted > 0
}

internal fun shouldStopWorkerSweep(results: List<WorkerLevelResult>, minimumGain: Double = .03): Boolean {
    if (results.size < 3) return false
    val previous = results[results.lastIndex - 2]
    val penultimate = results[results.lastIndex - 1]
    val latest = results.last()
    fun noGain(a: WorkerLevelResult, b: WorkerLevelResult): Boolean =
        !b.valid || !a.valid || b.jobsPerSecond <= a.jobsPerSecond * (1.0 + minimumGain) || b.p95Ms > a.p95Ms * 1.22
    return noGain(previous, penultimate) && noGain(penultimate, latest)
}

internal fun recommendedWorkerLevel(results: List<WorkerLevelResult>): WorkerLevelResult? = results
    .filter(WorkerLevelResult::valid)
    .maxWithOrNull(compareBy<WorkerLevelResult> { it.jobsPerSecond }.thenBy { -it.p95Ms })

internal fun benchmarkFallbackReason(
    cancelled: Boolean,
    peakRecommendation: WorkerLevelResult?,
    sustained: WorkerLevelResult?
): String? = when {
    cancelled -> "тест отменён"
    peakRecommendation == null -> "ни один уровень worker-ов не прошёл проверку корректности"
    sustained == null -> "sustained-проверка не выполнена"
    !sustained.valid -> "sustained-проверка не прошла проверку корректности (${sustained.correct}/${sustained.attempted}, errors=${sustained.errors})"
    else -> null
}
