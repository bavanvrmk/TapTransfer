package com.taptransfer

import org.junit.Assert.*
import org.junit.Test

class TripLedgerTest {
    private val base=Trip(tripId(),"Goa",listOf("Anu","Ben","Dev"),"Ben")
    private fun row(k:String,paid:Map<String,Long>,owed:Map<String,Long>)=
        TripEntry(tripId(),1,k,"Booking","Cash",paid,owed,by="Dev")
    @Test fun walletAndHandRepaymentDoNotInflateSpending() {
        val deposit=row("Contribution",mapOf("Anu" to 10000L),mapOf(POT to 10000L))
        val lunch=row("Expense",mapOf(POT to 6000L,"Ben" to 3000L),base.members.associateWith{3000L})
        val refund=row("Withdrawal",mapOf(POT to 4000L),mapOf("Anu" to 4000L))
        val payment=row("Repayment",mapOf("Dev" to 3000L),mapOf("Anu" to 3000L))
        val before=base.copy(entries=listOf(deposit,lunch,refund)).totals()
        assertEquals(0L,before.wallet);assertEquals(listOf(Triple("Dev","Anu",3000L)),settlements(before.balances))
        val after=base.copy(entries=listOf(deposit,lunch,refund,payment)).totals()
        assertEquals(9000L,after.spent);assertTrue(after.balances.values.all{it==0L})
    }
    @Test fun fractionsKeepEveryPaisa() {
        for(total in listOf(1L,2L,100L,9999999999L)) for(mode in listOf(Split.Equal,Split.Percent,Split.Shares)) {
            val inputs=if(mode==Split.Percent) mapOf("A" to "12.35","B" to "87.65") else mapOf("A" to "2","B" to "7")
            assertEquals(total,allocate(total,inputs,mode).values.sum())
        }
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsBadPercentage(){allocate(200L,mapOf("A" to "99"),Split.Percent)}
    @Test(expected=IllegalArgumentException::class) fun rejectsBadExact(){allocate(200L,mapOf("A" to "1"),Split.Exact)}
    @Test fun independentBillsMergeIdempotently(){
        val a=base.copy(entries=listOf(row("Expense",mapOf("Anu" to 30L),mapOf("Dev" to 30L))))
        val b=base.copy(entries=listOf(row("Expense",mapOf("Dev" to 50L),mapOf("Ben" to 50L))))
        val merged=a.merge(b)
        assertEquals(80L,merged.totals().spent);assertEquals(merged,merged.merge(a));assertEquals(merged,b.merge(a))
    }
    @Test(expected=IllegalArgumentException::class) fun concurrentWalletOverspendRejected() {
        val common=base.copy(entries=listOf(row("Contribution",mapOf("Anu" to 100L),mapOf(POT to 100L))))
        fun spend()=common.copy(entries=common.entries+row("Expense",mapOf(POT to 80L),mapOf("Ben" to 80L)))
        spend().merge(spend())
    }
    @Test(expected=IllegalArgumentException::class) fun tamperedLineRejected(){
        base.copy(entries=listOf(row("Expense",mapOf("Ben" to 20L),mapOf("Anu" to 20L)).copy(items=listOf(TripLine("Tea",mapOf("Dev" to 20L)))))).totals()
    }

    @Test fun correctionKeepsAuditButRemovesCost() {
        val food=row("Expense",mapOf("Ben" to 300L),mapOf("Anu" to 300L))
        val undo=row("Correction",food.owed,food.paid).copy(reverses=food.id)
        val totals=base.copy(entries=listOf(food,undo)).totals()
        assertEquals(0L,totals.spent)
        assertTrue(totals.balances.values.all{it==0L})
    }
    @Test fun exchangeRoundsToNearestPaisa() { assertEquals("12.35",convertedInr("1","12.345")) }

    @Test fun driveCopyPreservesReceiptAndRejectsConflictingReceiptForSameEntry() {
        val expense=row("Expense",mapOf("Anu" to 1000L),mapOf("Ben" to 1000L)).copy(receipt="JVBERi0=")
        val local=base.copy(entries=listOf(expense))
        val extra=row("Expense",mapOf("Ben" to 200L),mapOf("Dev" to 200L))
        val imported=local.merge(base.copy(entries=listOf(expense,extra)))
        assertEquals(2,imported.merge(local).entries.size)
        assertEquals(expense.receipt,imported.entries.single{it.id==expense.id}.receipt)
        assertThrows(IllegalArgumentException::class.java) {
            imported.merge(base.copy(entries=listOf(expense.copy(receipt="different"))))
        }
        assertEquals(1,local.entries.size)
    }

    @Test fun batchImportValidatesFinalWalletInEitherOrder() {
        val cash = row("Contribution", mapOf("Anu" to 100L), mapOf(POT to 100L))
        val original = base.copy(entries=listOf(cash))
        fun purchase() = original.copy(entries=original.entries+row("Expense",mapOf(POT to 80L),mapOf("Ben" to 80L)))
        val a = purchase()
        val b = purchase()
        val topUp = original.copy(entries=original.entries+row("Contribution",mapOf("Dev" to 70L),mapOf(POT to 70L)))
        val result = original.mergeCopies(sequenceOf(a,b,topUp))
        assertEquals(10L,result.totals().wallet)
        assertEquals(result, original.mergeCopies(sequenceOf(topUp,b,a,a)))
    }

    @Test fun assignmentPostsOnlyAfterConfirmation() {
        val bill=row("Assigned",mapOf("Ben" to 3000L),mapOf("Anu" to 3000L))
        val assigned=base.copy(entries=listOf(bill))
        assertEquals(0L,assigned.totals().spent)
        assertTrue(assigned.totals().balances.values.all{it==0L})
        assertEquals(listOf(bill),assigned.openAssignments())
        val payment=bill.resolveBill(true,"Ben","Cash")
        val paid=assigned.copy(entries=listOf(bill,payment))
        assertEquals(3000L,paid.totals().spent)
        assertEquals(3000L,paid.totals().balances["Ben"])
        assertTrue(paid.openAssignments().isEmpty())
        assertEquals(3000L,paid.merge(assigned).totals().spent)
        assertThrows(IllegalArgumentException::class.java){paid.copy(entries=paid.entries+bill.resolveBill(true,"Anu","UPI")).totals()}
    }
    @Test fun cancelledAssignmentDoesNotChangeNet() {
        val bill=row("Assigned",mapOf("Dev" to 550L),mapOf("Ben" to 550L))
        val cancelled=base.copy(entries=listOf(bill,bill.resolveBill(false,"Anu","Other")))
        assertEquals(0L,cancelled.totals().spent)
        assertTrue(cancelled.openAssignments().isEmpty())
        assertTrue(cancelled.totals().balances.values.all{it==0L})
    }
}
