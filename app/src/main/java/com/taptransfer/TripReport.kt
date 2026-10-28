package com.taptransfer

fun Trip.report():String {
    fun cell(value:String):String {
        val safe=if(value.trimStart().firstOrNull() in listOf('=','+','-','@')) "'$value" else value
        return "\""+safe.replace("\"","\"\"")+"\""
    }
    val rows=mutableListOf<List<String>>()
    rows+=listOf("Trip",name,"Wallet keeper",keeper)
    rows+=listOf("Date","Type","Description","Method","Recorded by","Member / wallet","Paid INR","Allocated / received INR","Reference","Original currency","Assigned amount INR")
    for(e in entries.sortedBy{it.at}) {
        for(k in (e.paid.keys+e.owed.keys)) rows+=listOf(java.time.Instant.ofEpochMilli(e.at).toString(),e.kind,e.note,e.method,e.by,
            if(k==POT)"Group wallet" else k,if(e.kind in setOf("Assigned","Cancelled")) "" else rupees(e.paid[k] ?: 0),if(e.kind in setOf("Assigned","Cancelled")) "" else rupees(e.owed[k] ?: 0),e.id,e.foreign,if(e.kind=="Assigned") rupees(e.paid[k] ?: 0) else "")
    }
    val t=totals()
    rows+=listOf("Trip spending INR",rupees(t.spent))
    rows+=listOf("Remaining wallet INR",rupees(t.wallet))
    rows+=listOf("Member","Net balance INR")
    t.balances.forEach{(k,v)->rows+=listOf(k,rupees(v))}
    return "\uFEFF"+rows.joinToString("\r\n"){it.joinToString(",",transform=::cell)}
}

