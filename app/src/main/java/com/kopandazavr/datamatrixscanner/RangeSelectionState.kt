package com.kopandazavr.datamatrixscanner

/** Stable ID based state so list insertions do not silently change selection. */
internal data class RangeSelectionState(
    val selected: Set<Long> = emptySet(),
    val anchorId: Long? = null
) {
    val isActive: Boolean get() = selected.isNotEmpty()

    /**
     * A short tap toggles one row. Selecting it makes it the next range anchor.
     * Deselecting a non-anchor keeps the current boundary. If the anchor itself
     * is removed, the next selected row is preferred, then the preceding one.
     */
    fun toggle(id: Long, orderedIds: List<Long>): RangeSelectionState {
        if (id !in selected) return copy(selected = selected + id, anchorId = id)

        val remaining = selected - id
        if (remaining.isEmpty()) return RangeSelectionState()
        if (anchorId != id) return copy(selected = remaining)

        val removedIndex = orderedIds.indexOf(id)
        val replacement = if (removedIndex >= 0) {
            orderedIds.drop(removedIndex + 1).firstOrNull { it in remaining }
                ?: orderedIds.take(removedIndex).lastOrNull { it in remaining }
        } else {
            orderedIds.firstOrNull { it in remaining }
        }
        return RangeSelectionState(remaining, replacement)
    }

    /** Long press adds the inclusive anchor-to-target range without clearing other groups. */
    fun selectRange(targetId: Long, orderedIds: List<Long>): RangeSelectionState {
        val anchor = anchorId
        val anchorIndex = orderedIds.indexOf(anchor)
        val targetIndex = orderedIds.indexOf(targetId)
        if (!isActive || anchor == null || anchorIndex < 0 || targetIndex < 0) {
            return RangeSelectionState(selected + targetId, targetId)
        }

        val range = if (anchorIndex <= targetIndex) {
            orderedIds.subList(anchorIndex, targetIndex + 1)
        } else {
            orderedIds.subList(targetIndex, anchorIndex + 1)
        }
        return RangeSelectionState(selected + range, targetId)
    }
}
