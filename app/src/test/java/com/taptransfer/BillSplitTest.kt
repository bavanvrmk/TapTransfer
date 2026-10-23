package com.taptransfer
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class BillSplitTest {
    @Test fun unevenBillAllocatesEachPaisa() {
        assertEquals(listOf(SplitPart(2,"33.33"),SplitPart(1,"33.34")), splitBill("100",3))
    }
    @Test fun allSharesSumToOriginal() {
        for(count in 2..100) {
            val parts = splitBill("917.19",count)
            assertEquals(count, parts.sumOf { it.people })
            assertEquals(BigDecimal("917.19"),parts.sumOf { BigDecimal(it.amount) * it.people.toBigDecimal() })
        }
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsZeroShare() { splitBill("0.01",2) }
}
