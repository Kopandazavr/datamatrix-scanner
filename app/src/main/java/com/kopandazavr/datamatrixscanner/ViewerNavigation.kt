package com.kopandazavr.datamatrixscanner

internal fun nextViewerIndexAfterRemoval(currentIndex: Int, remainingCount: Int): Int? =
    if (remainingCount <= 0) null else currentIndex.coerceIn(0, remainingCount - 1)
