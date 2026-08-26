package com.kopandazavr.datamatrixscanner

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.kopandazavr.datamatrixscanner.scanner.DetectionBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class StatisticsViewMode { SHORT, FULL }
internal enum class StatisticsFocusKind { MANUAL, AUTO }

internal data class DetectionGeometry(
    val width: Float,
    val height: Float,
    val area: Float,
    val aspectRatio: Float,
    val squareDeviation: Float,
    val centerDistance: Float,
    val angleDeviation: Float?,
    val oppositeSideRatio: Float?
)

internal data class FocusStatisticsEvent(
    val kind: StatisticsFocusKind,
    val success: Boolean,
    val timeout: Boolean,
    val durationMs: Long,
    val lensSteps: Int,
    val startDistance: Float?,
    val endDistance: Float?,
    val targetSharpness: Float?,
    val apparentArea: Float?,
    val noCandidateFramesBefore: Int = 0,
    val reacquireFrames: Int? = null,
    val reacquireMs: Long? = null
)

internal data class PerformanceStageEvent(
    val preprocessMs: Double?,
    val decoderMs: Double?,
    val endToEndMs: Double?
)

internal data class VariantPortfolioEvent(
    val variant: String,
    val success: Boolean,
    val trackConfirmed: Boolean,
    val rawFailed: Boolean,
    val preprocessMs: Double?,
    val decoderMs: Double?
)

internal data class DistributionSnapshot(
    val count: Long,
    val p10: Float?,
    val p50: Float?,
    val p90: Float?,
    val robustMin: Float?,
    val robustMax: Float?,
    val underflow: Long,
    val overflow: Long,
    val bins: LongArray
)

internal class NumericHistogram(
    val minValue: Float,
    val maxValue: Float,
    val bucketCount: Int
) {
    private val bins = LongArray(bucketCount)
    var count: Long = 0L
        private set
    var underflow: Long = 0L
        private set
    var overflow: Long = 0L
        private set

    fun add(value: Float?) {
        if (value == null || !value.isFinite()) return
        count++
        when {
            value < minValue -> underflow++
            value > maxValue -> overflow++
            else -> {
                val ratio = if (maxValue == minValue) 0f else (value - minValue) / (maxValue - minValue)
                val index = min(bucketCount - 1, (ratio * bucketCount).toInt().coerceAtLeast(0))
                bins[index]++
            }
        }
    }

    fun snapshot(): DistributionSnapshot = DistributionSnapshot(
        count = count,
        p10 = percentile(.10),
        p50 = percentile(.50),
        p90 = percentile(.90),
        robustMin = percentile(.02),
        robustMax = percentile(.98),
        underflow = underflow,
        overflow = overflow,
        bins = bins.copyOf()
    )

    private fun percentile(fraction: Double): Float? {
        if (count <= 0L) return null
        if (underflow >= ceil(count * fraction).toLong()) return minValue
        val target = ceil(count * fraction).toLong().coerceAtLeast(1L)
        var seen = underflow
        bins.forEachIndexed { index, amount ->
            seen += amount
            if (seen >= target) return minValue + (index + .5f) * (maxValue - minValue) / bucketCount
        }
        return maxValue
    }

    fun toJson(): JSONObject = JSONObject()
        .put("min", minValue.toDouble())
        .put("max", maxValue.toDouble())
        .put("count", count)
        .put("under", underflow)
        .put("over", overflow)
        .put("bins", JSONArray().also { array -> bins.forEach(array::put) })

    fun restore(json: JSONObject?) {
        if (json == null) return
        count = json.optLong("count", 0L)
        underflow = json.optLong("under", 0L)
        overflow = json.optLong("over", 0L)
        val array = json.optJSONArray("bins") ?: return
        for (index in bins.indices) bins[index] = array.optLong(index, 0L)
    }
}

private class GeometryAggregate {
    var count: Long = 0L
    val width = NumericHistogram(0f, .75f, 48)
    val height = NumericHistogram(0f, .75f, 48)
    val area = NumericHistogram(0f, .35f, 56)
    val aspect = NumericHistogram(1f, 4f, 48)
    val squareDeviation = NumericHistogram(0f, 2f, 40)
    val centerDistance = NumericHistogram(0f, .72f, 48)
    val angleDeviation = NumericHistogram(0f, 60f, 40)
    val oppositeRatio = NumericHistogram(1f, 4f, 48)

    fun add(geometry: DetectionGeometry) {
        count++
        width.add(geometry.width)
        height.add(geometry.height)
        area.add(geometry.area)
        aspect.add(geometry.aspectRatio)
        squareDeviation.add(geometry.squareDeviation)
        centerDistance.add(geometry.centerDistance)
        angleDeviation.add(geometry.angleDeviation)
        oppositeRatio.add(geometry.oppositeSideRatio)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("count", count)
        .put("width", width.toJson()).put("height", height.toJson()).put("area", area.toJson())
        .put("aspect", aspect.toJson()).put("square", squareDeviation.toJson())
        .put("center", centerDistance.toJson()).put("angle", angleDeviation.toJson())
        .put("opposite", oppositeRatio.toJson())

    fun restore(json: JSONObject?) {
        if (json == null) return
        count = json.optLong("count", 0L)
        width.restore(json.optJSONObject("width")); height.restore(json.optJSONObject("height"))
        area.restore(json.optJSONObject("area")); aspect.restore(json.optJSONObject("aspect"))
        squareDeviation.restore(json.optJSONObject("square")); centerDistance.restore(json.optJSONObject("center"))
        angleDeviation.restore(json.optJSONObject("angle")); oppositeRatio.restore(json.optJSONObject("opposite"))
    }
}

private class FocusAggregate {
    var launches = 0L
    var successes = 0L
    var failures = 0L
    var timeouts = 0L
    val duration = NumericHistogram(0f, 8_000f, 64)
    val steps = NumericHistogram(0f, 32f, 32)
    val startDistance = NumericHistogram(0f, 20f, 50)
    val endDistance = NumericHistogram(0f, 20f, 50)
    val noCandidateBefore = NumericHistogram(0f, 60f, 30)
    val reacquireFrames = NumericHistogram(0f, 60f, 30)
    val reacquireMs = NumericHistogram(0f, 6_000f, 48)

    fun add(event: FocusStatisticsEvent) {
        launches++
        if (event.success) successes++ else failures++
        if (event.timeout) timeouts++
        duration.add(event.durationMs.toFloat())
        steps.add(event.lensSteps.toFloat())
        startDistance.add(event.startDistance)
        endDistance.add(event.endDistance)
        noCandidateBefore.add(event.noCandidateFramesBefore.toFloat())
        reacquireFrames.add(event.reacquireFrames?.toFloat())
        reacquireMs.add(event.reacquireMs?.toFloat())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("launches", launches).put("success", successes).put("failure", failures).put("timeout", timeouts)
        .put("duration", duration.toJson()).put("steps", steps.toJson())
        .put("start", startDistance.toJson()).put("end", endDistance.toJson())
        .put("noCandidateBefore", noCandidateBefore.toJson())
        .put("reacquireFrames", reacquireFrames.toJson()).put("reacquireMs", reacquireMs.toJson())

    fun restore(json: JSONObject?) {
        if (json == null) return
        launches = json.optLong("launches", 0L); successes = json.optLong("success", 0L)
        failures = json.optLong("failure", 0L); timeouts = json.optLong("timeout", 0L)
        duration.restore(json.optJSONObject("duration")); steps.restore(json.optJSONObject("steps"))
        startDistance.restore(json.optJSONObject("start")); endDistance.restore(json.optJSONObject("end"))
        noCandidateBefore.restore(json.optJSONObject("noCandidateBefore"))
        reacquireFrames.restore(json.optJSONObject("reacquireFrames")); reacquireMs.restore(json.optJSONObject("reacquireMs"))
    }
}

private class PerformanceAggregate {
    var lastRunAtMs: Long = 0L
    var lastJobsPerSecond: Double = 0.0
    var lastWorkers: Int = 0
    var bestJobsPerSecond: Double = 0.0
    var bestWorkers: Int = 0
    var corpusVersion: String = "-"
    var pipelineVersion: String = "-"
    var baselineP50Ms: Double = 0.0
    var lastRecommendationValid: Boolean = false
    var lastFallbackReason: String = "-"
    val livePreprocess = NumericHistogram(0f, 500f, 64)
    val liveDecoder = NumericHistogram(0f, 2_000f, 80)
    val liveEndToEnd = NumericHistogram(0f, 3_000f, 96)

    fun addBenchmark(result: PerformanceBenchmarkResult) {
        lastRunAtMs = result.finishedAtMs
        lastJobsPerSecond = result.sustainedJobsPerSecond
        lastWorkers = result.recommendedWorkers
        corpusVersion = result.corpusVersion
        pipelineVersion = result.pipelineVersion
        lastRecommendationValid = result.recommendationValid
        lastFallbackReason = result.recommendationFallbackReason ?: "-"
        val selected = result.levels.lastOrNull { it.workers == result.recommendedWorkers }
            ?: result.levels.firstOrNull { it.workers == result.recommendedWorkers }
        if (result.sustainedJobsPerSecond > bestJobsPerSecond && !result.cancelled && result.recommendationValid) {
            bestJobsPerSecond = result.sustainedJobsPerSecond
            bestWorkers = result.recommendedWorkers
            baselineP50Ms = selected?.p50Ms ?: baselineP50Ms
        }
    }

    fun addStage(event: PerformanceStageEvent) {
        livePreprocess.add(event.preprocessMs?.toFloat())
        liveDecoder.add(event.decoderMs?.toFloat())
        liveEndToEnd.add(event.endToEndMs?.toFloat())
    }

    fun indexPercent(): Float? {
        val live = liveEndToEnd.snapshot().p50 ?: return null
        if (baselineP50Ms <= 0.0 || live <= 0f) return null
        return (baselineP50Ms / live * 100.0).toFloat().coerceIn(0f, 300f)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("lastRunAtMs", lastRunAtMs).put("lastJobsPerSecond", lastJobsPerSecond).put("lastWorkers", lastWorkers)
        .put("bestJobsPerSecond", bestJobsPerSecond).put("bestWorkers", bestWorkers)
        .put("corpusVersion", corpusVersion).put("pipelineVersion", pipelineVersion).put("baselineP50Ms", baselineP50Ms)
        .put("lastRecommendationValid", lastRecommendationValid).put("lastFallbackReason", lastFallbackReason)
        .put("livePreprocess", livePreprocess.toJson()).put("liveDecoder", liveDecoder.toJson()).put("liveEndToEnd", liveEndToEnd.toJson())

    fun restore(json: JSONObject?) {
        if (json == null) return
        lastRunAtMs = json.optLong("lastRunAtMs", 0L)
        lastJobsPerSecond = json.optDouble("lastJobsPerSecond", 0.0)
        lastWorkers = json.optInt("lastWorkers", 0)
        bestJobsPerSecond = json.optDouble("bestJobsPerSecond", 0.0)
        bestWorkers = json.optInt("bestWorkers", 0)
        corpusVersion = json.optString("corpusVersion", "-")
        pipelineVersion = json.optString("pipelineVersion", "-")
        baselineP50Ms = json.optDouble("baselineP50Ms", 0.0)
        lastRecommendationValid = json.optBoolean("lastRecommendationValid", false)
        lastFallbackReason = json.optString("lastFallbackReason", "-")
        livePreprocess.restore(json.optJSONObject("livePreprocess"))
        liveDecoder.restore(json.optJSONObject("liveDecoder"))
        liveEndToEnd.restore(json.optJSONObject("liveEndToEnd"))
    }
}

private class VariantPortfolioAggregate {
    var rawAttempts = 0L
    var rawFailures = 0L
    var enhancedAttempts = 0L
    var enhancedWins = 0L
    var confirmedEnhancedAttempts = 0L
    val preprocess = NumericHistogram(0f, 500f, 64)
    val decoder = NumericHistogram(0f, 2_000f, 80)
    val attemptsByVariant = linkedMapOf<String, Long>()
    val winsByVariant = linkedMapOf<String, Long>()

    fun add(event: VariantPortfolioEvent) {
        if (event.variant == "ORIGINAL") {
            rawAttempts++
            if (!event.success) rawFailures++
        } else {
            enhancedAttempts++
            if (event.success) enhancedWins++
            if (event.trackConfirmed) confirmedEnhancedAttempts++
        }
        attemptsByVariant[event.variant] = (attemptsByVariant[event.variant] ?: 0L) + 1L
        if (event.success) winsByVariant[event.variant] = (winsByVariant[event.variant] ?: 0L) + 1L
        preprocess.add(event.preprocessMs?.toFloat())
        decoder.add(event.decoderMs?.toFloat())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("rawAttempts", rawAttempts).put("rawFailures", rawFailures)
        .put("enhancedAttempts", enhancedAttempts).put("enhancedWins", enhancedWins)
        .put("confirmedEnhancedAttempts", confirmedEnhancedAttempts)
        .put("preprocess", preprocess.toJson()).put("decoder", decoder.toJson())
        .put("attemptsByVariant", JSONObject(attemptsByVariant as Map<*, *>))
        .put("winsByVariant", JSONObject(winsByVariant as Map<*, *>))

    fun restore(json: JSONObject?) {
        if (json == null) return
        rawAttempts = json.optLong("rawAttempts", 0L); rawFailures = json.optLong("rawFailures", 0L)
        enhancedAttempts = json.optLong("enhancedAttempts", 0L); enhancedWins = json.optLong("enhancedWins", 0L)
        confirmedEnhancedAttempts = json.optLong("confirmedEnhancedAttempts", 0L)
        preprocess.restore(json.optJSONObject("preprocess")); decoder.restore(json.optJSONObject("decoder"))
        restoreMap(json.optJSONObject("attemptsByVariant"), attemptsByVariant)
        restoreMap(json.optJSONObject("winsByVariant"), winsByVariant)
    }

    private fun restoreMap(json: JSONObject?, target: MutableMap<String, Long>) {
        json ?: return
        json.keys().forEach { key -> target[key] = json.optLong(key, 0L) }
    }
}

private class DeviceAggregate(val key: String) {
    var cameraId: String = "unknown"
    var physicalCameraId: String? = null
    var hardwareLevel: String = "unknown"
    var minimumFocusDistance: Float? = null
    var focusCalibration: String = "unknown"
    var afModes: String = "unknown"
    var maxRegionsAf: Int? = null
    val candidates = GeometryAggregate()
    val decoded = GeometryAggregate()
    val candidateLifetime = NumericHistogram(0f, 90f, 45)
    val decodedSharpness = NumericHistogram(0f, 100f, 50)
    val finalSharpness = NumericHistogram(0f, 100f, 50)
    val manualFocus = FocusAggregate()
    val autoFocus = FocusAggregate()
    val noCandidateRunFrames = NumericHistogram(0f, 120f, 60)
    val noCandidateRunMs = NumericHistogram(0f, 12_000f, 60)
    val apparentLensDistance = Array(APPARENT_SIZE_BUCKETS.size + 1) { NumericHistogram(0f, 20f, 50) }
    val focusEpisodes = mutableListOf<FocusEpisodeSample>()
    val afSizeSamples = mutableListOf<AfSizeSample>()
    val performance = PerformanceAggregate()
    val variantPortfolio = VariantPortfolioAggregate()
    var currentEpisodeId: Long? = null
    val currentEpisodeDistances = mutableListOf<Float>()
    val currentEpisodePayloads = linkedSetOf<String>()

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("cameraId", cameraId).put("physicalCameraId", physicalCameraId)
        .put("hardwareLevel", hardwareLevel).put("minimumFocusDistance", minimumFocusDistance)
        .put("focusCalibration", focusCalibration).put("afModes", afModes).put("maxRegionsAf", maxRegionsAf)
        .put("candidates", candidates.toJson()).put("decoded", decoded.toJson())
        .put("candidateLifetime", candidateLifetime.toJson())
        .put("decodedSharpness", decodedSharpness.toJson()).put("finalSharpness", finalSharpness.toJson())
        .put("manualFocus", manualFocus.toJson()).put("autoFocus", autoFocus.toJson())
        .put("noCandidateRunFrames", noCandidateRunFrames.toJson()).put("noCandidateRunMs", noCandidateRunMs.toJson())
        .put("apparentLens", JSONArray().also { array -> apparentLensDistance.forEach { array.put(it.toJson()) } })
        .put("focusEpisodes", JSONArray().also { array -> focusEpisodes.forEach { array.put(JSONObject().put("distance", it.distance.toDouble()).put("at", it.observedAtMs)) } })
        .put("afSizeSamples", JSONArray().also { array -> afSizeSamples.forEach { array.put(JSONObject().put("size", it.normalizedShortSide.toDouble()).put("episode", it.episodeId).put("passive", it.passiveHit)) } })
        .put("performance", performance.toJson())
        .put("variantPortfolio", variantPortfolio.toJson())

    fun restore(json: JSONObject) {
        cameraId = json.optString("cameraId", "unknown")
        physicalCameraId = json.optString("physicalCameraId").takeIf { it.isNotBlank() && it != "null" }
        hardwareLevel = json.optString("hardwareLevel", "unknown")
        minimumFocusDistance = json.optDouble("minimumFocusDistance", Double.NaN).toFloat().takeIf(Float::isFinite)
        focusCalibration = json.optString("focusCalibration", "unknown")
        afModes = json.optString("afModes", "unknown")
        maxRegionsAf = json.optInt("maxRegionsAf", -1).takeIf { it >= 0 }
        candidates.restore(json.optJSONObject("candidates")); decoded.restore(json.optJSONObject("decoded"))
        candidateLifetime.restore(json.optJSONObject("candidateLifetime"))
        decodedSharpness.restore(json.optJSONObject("decodedSharpness")); finalSharpness.restore(json.optJSONObject("finalSharpness"))
        manualFocus.restore(json.optJSONObject("manualFocus")); autoFocus.restore(json.optJSONObject("autoFocus"))
        noCandidateRunFrames.restore(json.optJSONObject("noCandidateRunFrames")); noCandidateRunMs.restore(json.optJSONObject("noCandidateRunMs"))
        val apparent = json.optJSONArray("apparentLens")
        if (apparent != null) for (index in apparentLensDistance.indices) apparentLensDistance[index].restore(apparent.optJSONObject(index))
        json.optJSONArray("focusEpisodes")?.let { array ->
            for (index in 0 until array.length()) array.optJSONObject(index)?.let { item ->
                item.optDouble("distance", Double.NaN).toFloat().takeIf(Float::isFinite)?.let { distance ->
                    focusEpisodes += FocusEpisodeSample(distance, item.optLong("at", 0L))
                }
            }
        }
        json.optJSONArray("afSizeSamples")?.let { array ->
            for (index in 0 until array.length()) array.optJSONObject(index)?.let { item ->
                item.optDouble("size", Double.NaN).toFloat().takeIf(Float::isFinite)?.let { size ->
                    afSizeSamples += AfSizeSample(size, item.optLong("episode", 0L), item.optBoolean("passive", true))
                }
            }
        }
        performance.restore(json.optJSONObject("performance"))
        variantPortfolio.restore(json.optJSONObject("variantPortfolio"))
    }

    fun addFocus(event: FocusStatisticsEvent) {
        if (event.kind == StatisticsFocusKind.MANUAL) manualFocus.add(event) else autoFocus.add(event)
        finalSharpness.add(event.targetSharpness)
        if (event.success && event.endDistance != null && event.apparentArea != null) {
            val apparentSize = sqrt(event.apparentArea.coerceAtLeast(0f))
            val index = APPARENT_SIZE_BUCKETS.indexOfFirst { apparentSize < it }.let { if (it < 0) APPARENT_SIZE_BUCKETS.size else it }
            apparentLensDistance[index].add(event.endDistance)
        }
    }

    fun addDecodedEvidence(
        geometry: DetectionGeometry,
        lensDistance: Float?,
        stationary: Boolean,
        episodeId: Long,
        payloadId: String,
        passiveHit: Boolean,
        nowMs: Long
    ) {
        if (currentEpisodeId != episodeId) {
            finalizeCurrentEpisode(nowMs)
            currentEpisodeId = episodeId
        }
        if (stationary && lensDistance != null && lensDistance.isFinite()) currentEpisodeDistances += lensDistance
        if (currentEpisodePayloads.add(payloadId)) {
            afSizeSamples += AfSizeSample(min(geometry.width, geometry.height), episodeId, passiveHit)
            while (afSizeSamples.size > 100) afSizeSamples.removeAt(0)
        }
    }

    fun learnedFocus(nowMs: Long): LearnedFocusRecommendation {
        val provisional = robustEpisodeDistance(currentEpisodeDistances)?.let { FocusEpisodeSample(it, nowMs) }
        return recommendLearnedFocus(focusEpisodes + listOfNotNull(provisional), minimumFocusDistance, nowMs)
    }

    fun afRegion(): AfRegionRecommendation = recommendAfRegion(afSizeSamples)

    private fun finalizeCurrentEpisode(nowMs: Long) {
        robustEpisodeDistance(currentEpisodeDistances)?.let { focusEpisodes += FocusEpisodeSample(it, nowMs) }
        while (focusEpisodes.size > 24) focusEpisodes.removeAt(0)
        currentEpisodeDistances.clear()
        currentEpisodePayloads.clear()
    }
}

internal class ScannerStatisticsStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dms-statistics").apply { isDaemon = true }
    }
    private val flushScheduled = AtomicBoolean(false)
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    private var resetAtMs = System.currentTimeMillis()
    private var cameraId = "unknown"
    private var physicalCameraId: String? = null
    private val devicePrefix = "${Build.MANUFACTURER}/${Build.MODEL}"
    private val devices = linkedMapOf<String, DeviceAggregate>()
    private var noCandidateFrames = 0
    private var candidateEvidenceFrames = 0
    private var noCandidateStartedElapsedMs = 0L

    init { restore(preferences.getString(PERSISTED_KEY, null)) }

    fun setCameraContext(
        cameraId: String,
        hardwareLevel: String,
        minimumFocusDistance: Float?,
        focusCalibration: String,
        afModes: String,
        maxRegionsAf: Int?
    ) = mutate {
        this.cameraId = cameraId
        val aggregate = current()
        aggregate.cameraId = cameraId
        aggregate.hardwareLevel = hardwareLevel
        aggregate.minimumFocusDistance = minimumFocusDistance
        aggregate.focusCalibration = focusCalibration
        aggregate.afModes = afModes
        aggregate.maxRegionsAf = maxRegionsAf
    }

    fun setPhysicalCameraId(value: String) {
        if (value.isBlank() || value == physicalCameraId) return
        mutate {
            physicalCameraId = value
            current().physicalCameraId = value
        }
    }

    fun recordCandidate(box: DetectionBox) {
        val geometry = detectionGeometry(box) ?: return
        mutate { current().candidates.add(geometry) }
    }

    fun recordCandidateLifetime(frames: Int) {
        if (frames <= 0) return
        mutate { current().candidateLifetime.add(frames.toFloat()) }
    }

    fun recordDecoded(
        box: DetectionBox,
        lensDistance: Float?,
        targetSharpness: Float?,
        stationary: Boolean = false,
        episodeId: Long = 0L,
        payloadId: String = box.key,
        passiveHit: Boolean = true
    ) {
        val geometry = detectionGeometry(box) ?: return
        mutate {
            val aggregate = current()
            aggregate.decoded.add(geometry)
            aggregate.decodedSharpness.add(targetSharpness)
            aggregate.addDecodedEvidence(
                geometry = geometry,
                lensDistance = lensDistance,
                stationary = stationary,
                episodeId = episodeId,
                payloadId = payloadId,
                passiveHit = passiveHit,
                nowMs = System.currentTimeMillis()
            )
            if (lensDistance != null) {
                val apparentSize = sqrt(geometry.area.coerceAtLeast(0f))
                val index = APPARENT_SIZE_BUCKETS.indexOfFirst { apparentSize < it }
                    .let { if (it < 0) APPARENT_SIZE_BUCKETS.size else it }
                aggregate.apparentLensDistance[index].add(lensDistance)
            }
        }
    }

    fun recordFocus(event: FocusStatisticsEvent) = mutate { current().addFocus(event) }

    fun recordPerformanceStage(event: PerformanceStageEvent) = mutate { current().performance.addStage(event) }

    fun recordPerformanceBenchmark(result: PerformanceBenchmarkResult) = mutate { current().performance.addBenchmark(result) }

    fun recordVariantPortfolio(event: VariantPortfolioEvent) = mutate { current().variantPortfolio.add(event) }

    fun recordCandidateEvidence(hasEvidence: Boolean, elapsedMs: Long) {
        var completed = false
        synchronized(lock) {
            if (!hasEvidence) {
                candidateEvidenceFrames = 0
                if (noCandidateFrames == 0) noCandidateStartedElapsedMs = elapsedMs
                noCandidateFrames++
            } else {
                candidateEvidenceFrames++
                if (noCandidateFrames > 0) {
                    val aggregate = current()
                    aggregate.noCandidateRunFrames.add(noCandidateFrames.toFloat())
                    aggregate.noCandidateRunMs.add((elapsedMs - noCandidateStartedElapsedMs).coerceAtLeast(0L).toFloat())
                    noCandidateFrames = 0
                    noCandidateStartedElapsedMs = 0L
                    completed = true
                }
            }
        }
        if (completed) changed()
    }

    fun currentNoCandidateFrames(): Int = synchronized(lock) { noCandidateFrames }

    fun currentCandidateEvidenceFrames(): Int = synchronized(lock) { candidateEvidenceFrames }

    fun recommendedAfRegionSize(): Float = synchronized(lock) { current().afRegion().normalizedSize }

    fun learnedFocusRecommendation(): LearnedFocusRecommendation = synchronized(lock) {
        current().learnedFocus(System.currentTimeMillis())
    }

    fun format(mode: StatisticsViewMode): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        val header = if (mode == StatisticsViewMode.SHORT) buildString {
            appendLine("Data Matrix Scanner — краткая статистика")
            appendLine("Версия приложения: ${appVersion()}")
            appendLine("Устройство: $devicePrefix")
            appendLine("Период: с ${formatTime(resetAtMs)} по ${formatTime(now)}")
        } else buildString {
            appendLine("Data Matrix Scanner — полная техническая статистика")
            appendLine("schema=$SCHEMA_VERSION app=${appVersion()} device=$devicePrefix")
            appendLine("resetAt=${formatTime(resetAtMs)} generatedAt=${formatTime(now)}")
            appendLine("groups=${devices.size} currentCamera=$cameraId physical=${physicalCameraId ?: "-"}")
        }
        header + devices.values.joinToString("\n") { aggregate ->
            if (mode == StatisticsViewMode.SHORT) formatShort(aggregate) else formatFull(aggregate)
        }.ifEmpty { "Данных пока нет.\n" }
    }

    fun reset() {
        synchronized(lock) {
            devices.clear()
            resetAtMs = System.currentTimeMillis()
            noCandidateFrames = 0
            candidateEvidenceFrames = 0
            noCandidateStartedElapsedMs = 0L
        }
        preferences.edit().remove(PERSISTED_KEY).apply()
        _version.value += 1L
    }

    override fun close() {
        flushNow()
        worker.shutdown()
    }

    private fun current(): DeviceAggregate {
        val key = "$devicePrefix|$cameraId|${physicalCameraId ?: "logical"}"
        return devices.getOrPut(key) { DeviceAggregate(key).also { it.cameraId = cameraId; it.physicalCameraId = physicalCameraId } }
    }

    private inline fun mutate(block: () -> Unit) {
        synchronized(lock) { block() }
        changed()
    }

    private fun changed() {
        _version.value += 1L
        if (flushScheduled.compareAndSet(false, true)) {
            worker.schedule({ flushNow() }, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun flushNow() {
        val encoded = synchronized(lock) { serialize() }
        preferences.edit().putString(PERSISTED_KEY, encoded).apply()
        flushScheduled.set(false)
    }

    private fun serialize(): String = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("resetAt", resetAtMs)
        .put("cameraId", cameraId)
        .put("physicalCameraId", physicalCameraId)
        .put("devices", JSONArray().also { array -> devices.values.forEach { array.put(it.toJson()) } })
        .toString()

    private fun restore(value: String?) {
        if (value.isNullOrBlank()) return
        runCatching {
            val root = JSONObject(value)
            if (root.optInt("schema", -1) !in 1..SCHEMA_VERSION) return@runCatching
            resetAtMs = root.optLong("resetAt", resetAtMs)
            cameraId = root.optString("cameraId", "unknown")
            physicalCameraId = root.optString("physicalCameraId").takeIf { it.isNotBlank() && it != "null" }
            val array = root.optJSONArray("devices") ?: return@runCatching
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val key = json.optString("key").takeIf(String::isNotBlank) ?: continue
                devices[key] = DeviceAggregate(key).also { it.restore(json) }
            }
        }
    }

    private fun formatShort(a: DeviceAggregate): String = buildString {
        val learned = a.learnedFocus(System.currentTimeMillis())
        val af = a.afRegion()
        val candidateLifetime = a.candidateLifetime.snapshot()
        val lossFrames = a.noCandidateRunFrames.snapshot()
        val lossMs = a.noCandidateRunMs.snapshot()
        val manualDuration = a.manualFocus.duration.snapshot()
        val autoDuration = a.autoFocus.duration.snapshot()
        val performanceIndex = a.performance.indexPercent()
        val focusSamples = a.manualFocus.launches + a.autoFocus.launches
        val enoughDecoded = a.decoded.count >= 20
        val enoughFocus = focusSamples >= 10
        val enoughLossRuns = lossFrames.count >= 10
        val enoughPerformance = a.performance.bestJobsPerSecond > 0.0 && a.performance.liveEndToEnd.count >= 10

        appendLine("\n## Общие данные")
        appendLine("Камера: ${a.cameraId}; физическая: ${a.physicalCameraId ?: "не указана"}; уровень: ${a.hardwareLevel}")
        appendLine("Диапазон ручного фокуса: 0…${fmt(a.minimumFocusDistance)} диоптрий")
        appendLine("ℹ Ноль означает фокус на бесконечность; большее число — более близкую дистанцию.")

        appendLine("## Распознавание")
        appendLine("Распознано Data Matrix: ${a.decoded.count}")
        appendLine("Найдено кандидатов: ${a.candidates.count}")
        appendLine("Резкость распознанных символов: медиана ${fmt(a.decodedSharpness.snapshot().p50)}, p90 ${fmt(a.decodedSharpness.snapshot().p90)}")
        appendLine("ℹ Медиана (p50) делит наблюдения пополам; p90 — значение, ниже которого лежат 90% наблюдений.")

        appendLine("## Кандидаты")
        appendLine("Типичный срок жизни кандидата: ${fmt(candidateLifetime.p50)} кадр.; p90 ${fmt(candidateLifetime.p90)} кадр.")
        appendLine("Типичная площадь распознанного символа: ${fmt(a.decoded.area.snapshot().p50)} доли кадра")
        appendLine("Рекомендованный размер области AF: ${fmt(af.normalizedSize)}; выборок ${af.sampleCount}, эпизодов ${af.supportingEpisodes}")
        appendLine("ℹ Срок жизни показывает устойчивость кандидата между кадрами; результат AF надёжен только при достаточном числе разных эпизодов.")

        appendLine("## Фокус")
        appendLine(focusHumanLine("Ручной", a.manualFocus, manualDuration))
        appendLine(focusHumanLine("Нативный AF", a.autoFocus, autoDuration))
        appendLine("Обученный ориентир: ${fmt(learned.anchor)}; рабочая зона ${fmt(learned.workingLow)}…${fmt(learned.workingHigh)}; уверенность ${percent01(learned.confidence)}")
        appendLine("ℹ Процент успеха считается по завершённым сеансам. Уверенность растёт, когда повторяются успешные независимые эпизоды.")

        appendLine("## Потеря и возврат кандидатов")
        appendLine("Зафиксировано эпизодов потери: ${lossFrames.count}")
        appendLine("Длительность потери: медиана ${fmt(lossFrames.p50)} кадр. / ${fmt(lossMs.p50)} мс; p90 ${fmt(lossFrames.p90)} кадр. / ${fmt(lossMs.p90)} мс")
        appendLine("ℹ Чем меньше время возврата, тем быстрее система снова находит объект после потери.")

        appendLine("## Производительность")
        appendLine("Индекс производительности: ${performanceIndex?.let { "${fmt(it)}%" } ?: "недостаточно данных"}")
        if (!a.performance.lastRecommendationValid && a.performance.lastRunAtMs > 0L) {
            appendLine("Последняя калибровка не состоялась: ${a.performance.lastFallbackReason}; baseline и worker policy не изменены")
        }
        appendLine("Лучший валидный замер: ${a.performance.bestWorkers.takeIf { it > 0 } ?: "—"} worker; ${fmt(a.performance.bestJobsPerSecond.toFloat())} задач/с")
        appendLine("Задержка live pipeline: медиана ${fmt(a.performance.liveEndToEnd.snapshot().p50)} мс; p90 ${fmt(a.performance.liveEndToEnd.snapshot().p90)} мс")
        appendLine("ℹ 100% означает уровень локального benchmark-базиса; выше — быстрее, ниже — медленнее. Без benchmark индекс не вычисляется.")

        appendLine("## Цифровые варианты")
        appendLine("Оригинал: попыток ${a.variantPortfolio.rawAttempts}, неудач ${a.variantPortfolio.rawFailures}")
        appendLine("Предобработанные варианты: попыток ${a.variantPortfolio.enhancedAttempts}, побед ${a.variantPortfolio.enhancedWins}, на подтверждённых треках ${a.variantPortfolio.confirmedEnhancedAttempts}")
        appendLine("ℹ Победа варианта означает, что декодирование удалось после цифровой предобработки.")

        appendLine("## Достаточность данных")
        appendLine("Распознавание: ${adequacy(enoughDecoded, a.decoded.count, 20)}")
        appendLine("Фокус: ${adequacy(enoughFocus, focusSamples, 10)}")
        appendLine("Потери кандидатов: ${adequacy(enoughLossRuns, lossFrames.count, 10)}")
        appendLine("Производительность: ${if (enoughPerformance) "достаточно для предварительной оценки" else "нужен завершённый benchmark и не менее 10 live-замеров"}")
        appendLine("ℹ Малые выборки пригодны для диагностики отдельных случаев, но по ним нельзя уверенно сравнивать версии.")
    }

    private fun formatFull(a: DeviceAggregate): String = buildString {
        appendLine("\n## Device/camera group")
        appendLine("[${a.key}]")
        appendLine("### Context")
        appendLine("camera=${a.cameraId} physical=${a.physicalCameraId ?: "-"} hw=${a.hardwareLevel} minFocus=${fmt(a.minimumFocusDistance)}")
        appendLine("context calibration=${a.focusCalibration} afModes=${a.afModes} maxRegionsAf=${a.maxRegionsAf ?: -1}")
        appendLine("### Candidate geometry")
        geometryFull("candidate", a.candidates).forEach(::appendLine)
        appendLine("### Decoded geometry")
        geometryFull("decoded", a.decoded).forEach(::appendLine)
        appendLine("### Candidate lifetime and sharpness")
        appendLine(distributionLine("candidate.lifetimeFrames", a.candidateLifetime))
        appendLine(distributionLine("sharp.decoded", a.decodedSharpness))
        appendLine(distributionLine("sharp.finalFocus", a.finalSharpness))
        appendLine("### Focus sessions")
        focusFull("focus.manual", a.manualFocus).forEach(::appendLine)
        focusFull("focus.auto", a.autoFocus).forEach(::appendLine)
        val learned = a.learnedFocus(System.currentTimeMillis())
        val af = a.afRegion()
        appendLine("learnedFocus episodes=${learned.validEpisodes} anchor=${fmt(learned.anchor)} band=${fmt(learned.workingLow)}..${fmt(learned.workingHigh)} confidence=${fmt(learned.confidence)} reason=${learned.reason}")
        appendLine("recommendedAfRegion=${fmt(af.normalizedSize)} samples=${af.sampleCount} episodes=${af.supportingEpisodes} reason=${af.reason}")
        appendLine("### Candidate loss")
        appendLine(distributionLine("noCandidate.frames", a.noCandidateRunFrames))
        appendLine(distributionLine("noCandidate.ms", a.noCandidateRunMs))
        appendLine("### Lens distance by apparent size")
        a.apparentLensDistance.forEachIndexed { index, histogram ->
            val upper = APPARENT_SIZE_BUCKETS.getOrNull(index)?.let(::fmt) ?: "+inf"
            appendLine(distributionLine("lensByApparentSize.bucket$index(<$upper)", histogram))
        }
        appendLine("### Performance")
        appendLine("performance baseline corpus=${a.performance.corpusVersion} pipeline=${a.performance.pipelineVersion} lastWorkers=${a.performance.lastWorkers} lastJobsPerSec=${fmt(a.performance.lastJobsPerSecond.toFloat())} recommendationValid=${if (a.performance.lastRecommendationValid) 1 else 0} fallbackReason=${a.performance.lastFallbackReason} bestWorkers=${a.performance.bestWorkers} bestJobsPerSec=${fmt(a.performance.bestJobsPerSecond.toFloat())} lastRunAt=${a.performance.lastRunAtMs}")
        appendLine("performance index=${fmt(a.performance.indexPercent())}%")
        appendLine(distributionLine("performance.live.preprocessMs", a.performance.livePreprocess))
        appendLine(distributionLine("performance.live.decoderMs", a.performance.liveDecoder))
        appendLine(distributionLine("performance.live.endToEndMs", a.performance.liveEndToEnd))
        appendLine("### Digital variants")
        appendLine("variantPortfolio raw=${a.variantPortfolio.rawAttempts} rawFail=${a.variantPortfolio.rawFailures} enhanced=${a.variantPortfolio.enhancedAttempts} wins=${a.variantPortfolio.enhancedWins} confirmedEnhanced=${a.variantPortfolio.confirmedEnhancedAttempts}")
        appendLine("variantPortfolio attempts=${a.variantPortfolio.attemptsByVariant} wins=${a.variantPortfolio.winsByVariant}")
        appendLine(distributionLine("variantPortfolio.preprocessMs", a.variantPortfolio.preprocess))
        appendLine(distributionLine("variantPortfolio.decoderMs", a.variantPortfolio.decoder))
    }

    private fun geometryFull(prefix: String, value: GeometryAggregate): List<String> = listOf(
        distributionLine("$prefix.width", value.width), distributionLine("$prefix.height", value.height),
        distributionLine("$prefix.area", value.area), distributionLine("$prefix.aspect", value.aspect),
        distributionLine("$prefix.squareDeviation", value.squareDeviation),
        distributionLine("$prefix.centerDistance", value.centerDistance),
        distributionLine("$prefix.angleDeviationDeg", value.angleDeviation),
        distributionLine("$prefix.oppositeSideRatio", value.oppositeRatio)
    )

    private fun focusFull(prefix: String, value: FocusAggregate): List<String> = listOf(
        "$prefix counts launch=${value.launches} success=${value.successes} fail=${value.failures} timeout=${value.timeouts}",
        distributionLine("$prefix.durationMs", value.duration), distributionLine("$prefix.steps", value.steps),
        distributionLine("$prefix.startDistance", value.startDistance), distributionLine("$prefix.endDistance", value.endDistance),
        distributionLine("$prefix.noCandidateBefore", value.noCandidateBefore),
        distributionLine("$prefix.reacquireFrames", value.reacquireFrames), distributionLine("$prefix.reacquireMs", value.reacquireMs)
    )

    private fun focusHumanLine(
        title: String,
        value: FocusAggregate,
        duration: DistributionSnapshot
    ): String {
        val successRate = if (value.launches > 0) value.successes * 100f / value.launches else null
        return "$title: запусков ${value.launches}, успешно ${value.successes} (${successRate?.let { "${fmt(it)}%" } ?: "нет данных"}), " +
            "таймаутов ${value.timeouts}; длительность p50 ${fmt(duration.p50)} мс, p90 ${fmt(duration.p90)} мс"
    }

    private fun percent01(value: Float?): String =
        value?.takeIf(Float::isFinite)?.let { "${fmt(it.coerceIn(0f, 1f) * 100f)}%" } ?: "недостаточно данных"

    private fun adequacy(enough: Boolean, actual: Long, recommended: Long): String =
        if (enough) "достаточно для предварительной оценки ($actual наблюдений)"
        else "пока мало: $actual из рекомендуемых минимум $recommended"

    private fun brief(value: NumericHistogram): String {
        val s = value.snapshot()
        return "n=${s.count},p10=${fmt(s.p10)},p50=${fmt(s.p50)},p90=${fmt(s.p90)},robust=${fmt(s.robustMin)}..${fmt(s.robustMax)}"
    }

    private fun distributionLine(name: String, value: NumericHistogram): String {
        val s = value.snapshot()
        return "$name ${brief(value)} under=${s.underflow} over=${s.overflow} bins=${s.bins.joinToString(",")}"
    }

    private fun appVersion(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${info.versionName}/${PackageInfoCompat.getLongVersionCode(info)}"
    }.getOrDefault("unknown")

    private fun formatTime(value: Long): String = TIME_FORMAT.get().format(Date(value))

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val PREFERENCES_NAME = "scanner_statistics"
        private const val PERSISTED_KEY = "statistics_v1"
        private const val FLUSH_DELAY_SECONDS = 4L
        private val TIME_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    }
}

internal fun detectionGeometry(box: DetectionBox): DetectionGeometry? {
    if (box.points.size < 4) return null
    val xs = box.points.map { it.x.coerceIn(0f, 1f) }
    val ys = box.points.map { it.y.coerceIn(0f, 1f) }
    val width = (xs.maxOrNull()!! - xs.minOrNull()!!).coerceAtLeast(0f)
    val height = (ys.maxOrNull()!! - ys.minOrNull()!!).coerceAtLeast(0f)
    if (width < .001f || height < .001f) return null
    val area = width * height
    val cx = xs.average().toFloat()
    val cy = ys.average().toFloat()
    val centerDistance = sqrt((cx - .5f) * (cx - .5f) + (cy - .5f) * (cy - .5f))
    val aspectScale = box.imageAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
    val points = box.points.take(4).map { Pair(it.x * aspectScale, it.y) }
    val sides = points.indices.map { index ->
        val a = points[index]
        val b = points[(index + 1) % points.size]
        sqrt((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second))
    }
    val longest = sides.maxOrNull() ?: return null
    val shortest = sides.minOrNull()?.takeIf { it > .0001f } ?: return null
    val aspect = longest / shortest
    val angles = points.indices.mapNotNull { index ->
        val previous = points[(index + points.size - 1) % points.size]
        val current = points[index]
        val next = points[(index + 1) % points.size]
        val ax = previous.first - current.first
        val ay = previous.second - current.second
        val bx = next.first - current.first
        val by = next.second - current.second
        val denominator = sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by)
        if (denominator <= .000001f) null else Math.toDegrees(acos(((ax * bx + ay * by) / denominator).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }
    val oppositeRatio = max(ratio(sides[0], sides[2]), ratio(sides[1], sides[3]))
    return DetectionGeometry(
        width = width,
        height = height,
        area = area,
        aspectRatio = aspect,
        squareDeviation = abs(aspect - 1f),
        centerDistance = centerDistance,
        angleDeviation = angles.maxOfOrNull { abs(it - 90f) },
        oppositeSideRatio = oppositeRatio
    )
}

private fun ratio(a: Float, b: Float): Float {
    val small = min(a, b)
    return if (small <= .000001f) Float.NaN else max(a, b) / small
}

private fun fmt(value: Float?): String = value?.takeIf(Float::isFinite)?.let { String.format(Locale.US, "%.4f", it) } ?: "-"

private val APPARENT_SIZE_BUCKETS = floatArrayOf(.04f, .07f, .10f, .15f, .22f, .32f)
