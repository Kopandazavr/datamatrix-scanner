package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class BoostVariantPlanTest {
    @Test fun oneExpensiveIdentityJobHasSmallEarlyExitPortfolio() {
        assertEquals(
            listOf(VariantKind.ORIGINAL, VariantKind.CONTRAST_135, VariantKind.CLAHE),
            boostVariantPlan()
        )
    }
}
