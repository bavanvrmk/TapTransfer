package com.taptransfer

import java.math.BigDecimal

data class SplitPart(val people: Int, val amount: String)

fun splitBill(total: String, people: Int): List<SplitPart> {
    require(people in 2..100) { "Choose between 2 and 100 people." }
    require(total.matches(Regex("[0-9]{1,6}(\\.[0-9]{1,2})?"))) { "Enter the total in rupees, with up to two decimal places." }
    val paise = BigDecimal(total).movePointRight(2).intValueExact()
    require(paise in people..10000000) { "Each person needs at least one paisa. The maximum total is ₹1,00,000." }
    val base = paise / people
    val extra = paise % people
    fun part(count: Int, share: Int) = SplitPart(count, BigDecimal.valueOf(share.toLong(), 2).toPlainString())
    return buildList {
        add(part(people - extra, base))
        if (extra > 0) add(part(extra, base + 1))
    }
}
