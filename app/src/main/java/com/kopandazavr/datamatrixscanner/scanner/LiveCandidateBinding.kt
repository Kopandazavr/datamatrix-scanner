package com.kopandazavr.datamatrixscanner.scanner

internal data class BoundLiveCandidate(
    val candidateIndex: Int,
    val region: RecoveryRegion,
    val binding: CandidateEvidenceBinding
)

/** Keep the analyzer/evidence list index stable. Never sort/remerge before this binding step. */
internal fun bindLiveCandidates(
    regions: List<RecoveryRegion>,
    bindings: List<CandidateEvidenceBinding>,
    limit: Int = 12
): List<BoundLiveCandidate> {
    val byIndex = bindings.associateBy(CandidateEvidenceBinding::candidateIndex)
    return regions.mapIndexedNotNull { index, region ->
        byIndex[index]?.let { BoundLiveCandidate(index, region, it) }
    }.take(limit.coerceAtLeast(0))
}
