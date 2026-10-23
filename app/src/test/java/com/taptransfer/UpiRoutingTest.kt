package com.taptransfer
import org.junit.Assert.*
import org.junit.Test

class UpiRoutingTest {
    private val first = UpiTarget("other", "other/Pay", "Pay")
    private val selected = UpiTarget("preferred", "preferred/Pay", "Pay")
    private val choices = listOf(first, selected)

    @Test fun legacyPreferenceUsesSavedPackageEvenWhenLabelsMatch() {
        assertEquals(selected, UpiRouting.resolve("preferred", choices))
    }
    @Test fun exactComponentBeatsAnotherActivityInSameApp() {
        assertEquals(selected, UpiRouting.resolve(selected.component, listOf(selected.copy(component="preferred/Alias"))+choices))
    }
    @Test fun missingAppDoesNotSelectCompetitor() {
        assertNull(UpiRouting.resolve(selected.component, listOf(first)))
    }
    @Test fun renamedActivityStaysInsidePreferredApp() {
        assertEquals(selected, UpiRouting.resolve("preferred/Old", choices))
    }
    @Test fun askEveryTimeDoesNotPreselect() {
        assertNull(UpiRouting.resolve("", choices))
    }
}
