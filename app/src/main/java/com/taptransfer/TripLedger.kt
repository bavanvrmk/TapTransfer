package com.taptransfer

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

const val POT = "@wallet"
fun tripId() = UUID.randomUUID().toString()
fun rupees(paise: Long) = BigDecimal.valueOf(paise, 2).toPlainString()
fun paise(text: String): Long {
    require(text.matches(Regex("[0-9]{1,8}(\\.[0-9]{1,2})?"))) { "Enter a rupee amount with up to two decimal places." }
    return BigDecimal(text).movePointRight(2).longValueExact()
}
enum class Split { Equal, Exact, Percent, Shares }
fun allocate(total: Long, inputs: Map<String,String>, mode: Split): Map<String,Long> {
    require(total in 1..9999999999L) { "Enter a positive total." }
    val values = inputs.filterValues { it.isNotBlank() }.mapValues { (_,v) -> if(mode == Split.Equal) 1L else paise(v) }.filterValues { it>0 }.toSortedMap()
    require(values.isNotEmpty()) { "Select at least one participant." }
    val sum=values.values.sum()
    if(mode==Split.Exact) {
        require(sum==total) { "Shares must add up to ₹"+rupees(total)+"." }
        return values
    }
    if(mode==Split.Percent) require(sum==10000L) { "Percentages must add up to 100." }
    val parts=values.mapValues { (_,v) -> total.toBigInteger().multiply(v.toBigInteger()).divideAndRemainder(sum.toBigInteger()) }
    val result=parts.mapValues { it.value[0].toLong() }.toMutableMap()
    val remainder=(total-result.values.sum()).toInt()
    parts.keys.sortedWith(compareByDescending<String>{parts.getValue(it)[1]}.thenBy{it}).take(remainder).forEach { result[it]=result.getValue(it)+1 }
    return result.filterValues { it>0 }
}
data class TripLine(val label:String,val shares:Map<String,Long>)
data class TripEntry(val id:String, val at:Long, val kind:String, val note:String, val method:String,
    val paid:Map<String,Long>, val owed:Map<String,Long>, val receipt:String="", val by:String="",val items:List<TripLine> = emptyList(),val reverses:String="",val foreign:String="")
data class Trip(val id:String,val name:String,val members:List<String>,val keeper:String,val entries:List<TripEntry> = emptyList())
data class TripTotals(val spent:Long,val wallet:Long,val balances:Map<String,Long>)
fun Trip.totals():TripTotals {
    require(members.size in 2..30 && members.distinct().size==members.size && keeper in members &&
        members.all { it.isNotBlank() && it.length<=50 && it!=POT } && name.isNotBlank() && name.length<=80) { "Check trip name, keeper and members." }
    require(entries.size<=2000 && entries.distinctBy{it.id}.size==entries.size) { "Duplicate or excessive entries." }
    val net=(members+POT).associateWith{0L}.toMutableMap()
    val originals=entries.associateBy{it.id}
    val reversed=mutableSetOf<String>()
    val resolved=mutableSetOf<String>()
    var spent=0L
    for(e in entries) {
        val total=e.paid.values.sum()
        require(e.by in members && e.note.isNotBlank() && e.note.length<=200 && e.method in listOf("Cash","UPI","Card","Other")) { "Invalid entry details." }
        require(listOf(e.paid,e.owed).all { m->m.isNotEmpty() && m.all { (k,v)->k in net && v in 1..9999999999L } } &&
            total in 1..9999999999L && total==e.owed.values.sum()) { "Paid and allocated totals must match." }
        if(e.items.isNotEmpty()) {
            require(e.kind=="Expense" && e.items.size<=100)
            val accumulated=mutableMapOf<String,Long>()
            e.items.forEach { line->
                require(line.label.isNotBlank() && line.label.length<=80 && line.shares.isNotEmpty())
                line.shares.forEach { (k,v)->require(k in members && v in 1..9999999999L); accumulated[k]=(accumulated[k] ?: 0)+v }
            }
            require(accumulated==e.owed) { "Item shares do not match the bill." }
        }
        require(e.foreign.length<=100)
        if(e.kind !in setOf("Correction","Payment","Cancelled")) require(e.reverses.isEmpty())
        when(e.kind) {
            "Assigned" -> {
                require(e.paid.size==1 && POT !in e.paid && POT !in e.owed)
                continue
            }
            "Payment","Cancelled" -> {
                val bill=originals[e.reverses]
                require(bill?.kind=="Assigned" && bill.paid==e.paid && bill.owed==e.owed && resolved.add(bill.id)) { "This assignment has conflicting updates." }
                if(e.kind=="Cancelled") continue
                spent+=total
            }
            "Correction" -> {
                val original=originals[e.reverses] ?: error("Missing original entry.")
                require(original.kind !in setOf("Correction","Assigned","Cancelled") && reversed.add(original.id) && e.owed==original.paid && e.paid==original.owed) { "Invalid reversal." }
                if(original.kind in setOf("Expense","Payment"))spent-=total
            }
            "Expense" -> { require(POT !in e.owed); spent+=total }
            "Contribution","Withdrawal","Repayment" -> {
                val a=e.paid.keys.single(); val b=e.owed.keys.single()
                require(a!=b && when(e.kind) { "Contribution"->b==POT; "Withdrawal"->a==POT; else->a!=POT && b!=POT }) { "Choose different accounts for this transfer." }
            }
            else -> error("Unknown transaction type")
        }
        e.paid.forEach { (k,v)->net[k]=net.getValue(k)+v }
        e.owed.forEach { (k,v)->net[k]=net.getValue(k)-v }
    }
    val wallet=-net.getValue(POT)
    require(wallet>=0) { "The wallet has insufficient funds. Record the missing contribution first." }
    return TripTotals(spent,wallet,net.filterKeys { it!=POT })
}
fun Trip.merge(other: Trip): Trip = mergeCopies(sequenceOf(other))

fun Trip.mergeCopies(copies: Sequence<Trip>): Trip {
    val unique = entries.associateBy { it.id }.toMutableMap()
    var receiptChars = unique.values.sumOf { it.receipt.length.toLong() }
    for (snapshot in copies) {
        require(id == snapshot.id && name == snapshot.name && members == snapshot.members && keeper == snapshot.keeper) {
            "Choose copies of the same trip with matching members and wallet keeper."
        }
        for (entry in snapshot.entries) {
            val previous = unique[entry.id]
            if (previous == null) {
                receiptChars += entry.receipt.length
                require(unique.size < 2000 && receiptChars <= 24L * 1024 * 1024) { "Combined trip exceeds the trip limit." }
                unique[entry.id] = entry
            } else {
                require(previous == entry) { "Two copies disagree about an entry. Your local trip has been kept." }
            }
        }
    }
    val merged = copy(entries = unique.values.sortedWith(compareBy<TripEntry> { it.at }.thenBy { it.id }))
    merged.totals()
    return merged
}
fun settlements(balances:Map<String,Long>):List<Triple<String,String,Long>> {
    val net=balances.toMutableMap(); val result=mutableListOf<Triple<String,String,Long>>()
    while(true) {
        val from=net.minByOrNull{it.value} ?: break
        val to=net.maxByOrNull{it.value} ?: break
        val value=minOf(-from.value,to.value); if(value<=0) break
        result+=Triple(from.key,to.key,value); net[from.key]=from.value+value; net[to.key]=to.value-value
    }
    return result
}


fun convertedInr(original:String, rate:String):String {
    require(original.matches(Regex("[0-9]{1,8}(\\.[0-9]{1,3})?")) && rate.matches(Regex("[0-9]{1,6}(\\.[0-9]{1,6})?"))) { "Enter a positive amount and INR exchange rate." }
    val value=(BigDecimal(original)*BigDecimal(rate)).setScale(2,RoundingMode.HALF_UP).toPlainString()
    require(paise(value)>0) { "Converted amount is below one paisa." }
    return value
}


fun Trip.openAssignments(): List<TripEntry> {
    val resolved = entries.filter { it.kind=="Payment" || it.kind=="Cancelled" }.map { it.reverses }.toSet()
    return entries.filter { it.kind=="Assigned" && it.id !in resolved }
}
fun TripEntry.resolveBill(paidNow: Boolean, member: String, paymentMethod: String): TripEntry {
    require(kind=="Assigned")
    return copy(id=tripId(), at=System.currentTimeMillis(), kind=if(paidNow) "Payment" else "Cancelled",
        method=paymentMethod, by=member, receipt="", reverses=id)
}
