package com.taptransfer

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

const val TRIP_LIMIT = 24 * 1024 * 1024
fun InputStream.bounded(limit:Int):ByteArray = use { input ->
    val output=java.io.ByteArrayOutputStream()
    val buf=ByteArray(16384)
    while(true) {
        val n=input.read(buf); if(n<0) break
        require(output.size()+n<=limit) { "File too large. Use a smaller receipt or trip file." }
        output.write(buf,0,n)
    }
    output.toByteArray()
}
fun receiptType(bytes:ByteArray):String {
    if(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) return "application/pdf"
    if(bytes.size>=3 && bytes[0]==(-1).toByte() && bytes[1]==(-40).toByte() && bytes[2]==(-1).toByte()) return "image/jpeg"
    if(bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) return "image/png"
    error("Choose a PDF, JPEG or PNG receipt.")
}
fun Trip.encode():String {
    val rows=JSONArray()
    entries.forEach { e-> rows.put(JSONObject().put("id",e.id).put("at",e.at).put("kind",e.kind).put("note",e.note).put("method",e.method)
        .put("paid",JSONObject(e.paid)).put("owed",JSONObject(e.owed)).put("receipt",e.receipt).put("by",e.by).put("reverses",e.reverses).put("foreign",e.foreign).put("items",JSONArray(e.items.map{JSONObject().put("label",it.label).put("shares",JSONObject(it.shares))}))) }
    return JSONObject().put("version",1).put("id",id).put("name",name).put("members",JSONArray(members)).put("keeper",keeper).put("entries",rows).toString()
}
private fun uuid(value:String):String {
    require(UUID.fromString(value).toString()==value) { "Invalid trip file identifier." }; return value
}
fun decodeTrip(raw:String):Trip {
    val j=JSONObject(raw); require(j.getInt("version")==1) { "Unsupported trip file." }
    val people=j.getJSONArray("members"); val rows=j.getJSONArray("entries")
    require(people.length() in 2..30 && rows.length()<=2000)
    fun amounts(o:JSONObject)=o.keys().asSequence().associateWith { key->
        val v=o.get(key); require(v is Long || v is Int); (v as Number).toLong()
    }
    val entries=(0 until rows.length()).map { i->
        val e=rows.getJSONObject(i); val receipt=e.optString("receipt")
        if(receipt.isNotEmpty()) {
            require(receipt.length<=2800000); receiptType(Base64.decode(receipt,Base64.DEFAULT))
        }
        TripEntry(uuid(e.getString("id")),e.getLong("at"),e.getString("kind"),e.getString("note"),e.getString("method"),
            amounts(e.getJSONObject("paid")),amounts(e.getJSONObject("owed")),receipt,e.getString("by"),e.optJSONArray("items")?.let { lines ->
                require(lines.length()<=100)
                (0 until lines.length()).map { index->val line=lines.getJSONObject(index); TripLine(line.getString("label"),amounts(line.getJSONObject("shares"))) }
            }.orEmpty(),e.optString("reverses"),e.optString("foreign"))
    }
    return Trip(uuid(j.getString("id")),j.getString("name"),(0 until people.length()).map{people.getString(it)},j.getString("keeper"),entries).also { it.totals() }
}
class TripFiles(context:Context) {
    private val dir=File(context.filesDir,"trips").apply{mkdirs()}
    fun ids()=dir.listFiles().orEmpty().map{it.name.removeSuffix(".bak")}.filter{it.endsWith(".trip")}.distinct().map{it.removeSuffix(".trip")}
    fun delete(id:String) {
        val file=File(dir,uuid(id)+".trip")
        AtomicFile(file).delete()
        check(!file.exists() && !File(file.path+".bak").exists()) { "Could not delete this trip from the phone." }
    }
    fun read(id:String)=decodeTrip(AtomicFile(File(dir,uuid(id)+".trip")).openRead().bounded(TRIP_LIMIT).toString(Charsets.UTF_8))
    fun save(trip:Trip) {
        trip.totals()
        val bytes=trip.encode().toByteArray(); require(bytes.size<=TRIP_LIMIT) { "Trip file limit reached. Use smaller receipts." }
        val target=AtomicFile(File(dir,uuid(trip.id)+".trip"))
        val out=target.startWrite()
        try { out.write(bytes); target.finishWrite(out) } catch(e:Exception) { target.failWrite(out); throw e }
    }
}
