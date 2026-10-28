package com.taptransfer

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal fun sheetIdFrom(input:String):String {
    val text=input.trim()
    val match=Regex("""https://docs\.google\.com/spreadsheets/(?:u/\d+/)?d/([A-Za-z0-9_-]+)(?:[/?#].*)?""").matchEntire(text)
    val id=match?.groupValues?.get(1) ?: text
    require(id.matches(Regex("[A-Za-z0-9_-]{15,150}"))) { "Paste the Google Sheets link from your trip organizer." }
    return id
}
internal fun sheetLink(id:String)="https://docs.google.com/spreadsheets/d/"+sheetIdFrom(id)+"/edit"

private fun obj(vararg pairs:Pair<String,Any>):JSONObject = JSONObject().apply { pairs.forEach{put(it.first,it.second)} }
private fun arr(vararg values:Any)=JSONArray(values.toList())
private fun cell(text:String)=obj("userEnteredValue" to obj("stringValue" to text))
private fun row(vararg fields:String)=obj("values" to JSONArray(fields.map(::cell)))

/** Google owns access control. An atomic Sheets batch consumes the old revision before appending. */
internal class SharedSheet(private val token:String, private val transport:((String,String,JSONObject?)->JSONObject)?=null) {
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")

    private val docs="https://sheets.googleapis.com/v4/spreadsheets"
    private val drive="https://www.googleapis.com/drive/v3/files"
    internal class ApiError(val status:Int,msg:String):Exception(msg)
    private fun call(url:String, method:String="GET",body:JSONObject?=null):JSONObject {
        transport?.let { return it(url,method,body) }
        val connection=URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=15000;connection.readTimeout=45000
            connection.requestMethod=method;connection.setRequestProperty("Authorization","Bearer $token")
            if(body!=null) {
                val bytes=body.toString().toByteArray()
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use{it.write(bytes)}
            }
            val status=connection.responseCode
            if(status !in 200..299) throw ApiError(status,when(status){
                401->"Google authorization expired. Reconnect Google."
                403,404->"Google could not access this Sheet. Check the signed-in account, editor permission and enabled APIs."
                429->"Google's request limit was reached. Try again shortly."
                else->"Google Sheets returned HTTP $status."
            })
            val result=connection.inputStream.bounded(48*1024*1024).toString(Charsets.UTF_8)
            return if(result.isBlank())JSONObject() else JSONObject(result)
        } finally {connection.disconnect()}
    }
    private fun revision()="tt_"+tripId().replace("-","")
    private fun marker(rev:String, tabId:Int)=obj("addNamedRange" to obj("namedRange" to obj("name" to rev,"namedRangeId" to rev,
        "range" to obj("sheetId" to tabId,"startRowIndex" to 0,"endRowIndex" to 1,"startColumnIndex" to 0,"endColumnIndex" to 1))))
    private fun rows(t:Trip):JSONArray {
        val rows=JSONArray()
        fun shares(parts:Map<String,Long>)=parts.entries.joinToString { (k,v)->(if(k==POT)"Group wallet" else k)+" ₹"+rupees(v) }
        val data=JSONObject(t.encode()).getJSONArray("entries")
        for(i in t.entries.indices) {
            val e=t.entries[i];val chunks=java.util.Base64.getEncoder().encodeToString(data.getJSONObject(i).toString().toByteArray()).chunked(20000)
            chunks.forEachIndexed { n,chunk->
                rows.put(row(if(n==0)e.note else "",if(n==0)e.kind else "",if(n==0)rupees(e.paid.values.sum()) else "",
                    if(n==0)e.by else "",e.id,n.toString(),chunk,if(n==0)shares(e.paid) else "",if(n==0)shares(e.owed) else "",if(n==0)e.method else ""))
            }
        }
        return rows
    }
    private fun append(data:JSONArray, tabId:Int)=obj("appendCells" to obj("sheetId" to tabId,"rows" to data,"fields" to "userEnteredValue"))
    fun create(trip:Trip):String {
        val q="trashed = false and 'me' in owners and appProperties has { key='taptransferTrip' and value='"+trip.id+"' }"
        val candidates=call(drive+"?fields=files(id)&q="+encode(q)).getJSONArray("files")
        require(candidates.length()<=1){"More than one matching Sheet exists. Join the intended Sheet using its link."}
        val id=if(candidates.length()==1)candidates.getJSONObject(0).getString("id") else call(drive,"POST",
            obj("name" to ("TapTransfer · "+trip.name),"mimeType" to "application/vnd.google-apps.spreadsheet",
                "appProperties" to obj("taptransferTrip" to trip.id))).getString("id")
        val meta=call("$docs/$id?fields=namedRanges(name),sheets(properties(sheetId,title))")
        if(meta.optJSONArray("namedRanges")?.let{ a->(0 until a.length()).any{a.getJSONObject(it).optString("name").startsWith("tt_")} }==true) {
            sync(id,trip);return id
        }
        val props=meta.getJSONArray("sheets").getJSONObject(0).getJSONObject("properties")
        val tabId=props.getInt("sheetId")
        val initial=JSONArray().put(row("Bill / note","Entry type","Amount INR","Recorded by","Entry ID","Part","Ledger data","Payer / source","Split / destination","Method"))
            .put(row(trip.name,"Trip setup","","",trip.id,"0",trip.copy(entries=emptyList()).encode()))
        val extra=rows(trip);for(i in 0 until extra.length())initial.put(extra.getJSONObject(i))
        val req=arr(marker("trip_initialized",tabId),marker("tt_initial",tabId),obj("updateSheetProperties" to obj("properties" to obj("sheetId" to tabId,"title" to "Trip ledger",
            "gridProperties" to obj("frozenRowCount" to 1)),"fields" to "title,gridProperties.frozenRowCount")),
            append(initial,tabId),obj("updateDimensionProperties" to obj("range" to obj("sheetId" to tabId,"dimension" to "COLUMNS","startIndex" to 4,"endIndex" to 7),
                "properties" to obj("hiddenByUser" to true),"fields" to "hiddenByUser")))
        call("$docs/$id:batchUpdate","POST",obj("requests" to req));return id
    }
    fun sync(sheet:String,local:Trip?=null):Trip {
        val id=sheetIdFrom(sheet)
        repeat(3) {
            val names=call("$docs/$id?fields=namedRanges(namedRangeId,name,range(sheetId))").optJSONArray("namedRanges") ?: error("This Sheet has no TapTransfer ledger.")
            val currentMarker=(0 until names.length()).map{names.getJSONObject(it)}.singleOrNull{it.optString("name").startsWith("tt_")}
                ?: error("The trip revision is missing or ambiguous.")
            val revision=currentMarker.getString("namedRangeId")
            val tabId=currentMarker.getJSONObject("range").optInt("sheetId",0)
            val values=call("$docs/$id/values/"+encode("'Trip ledger'!E2:G")).getJSONArray("values")
            val header=decodeTrip(values.getJSONArray(0).getString(2))
            val packed=linkedMapOf<String,MutableList<String>>()
            var chars=0
            for(i in 1 until values.length()) {
                val r=values.getJSONArray(i);val chunks=packed.getOrPut(r.getString(0)){mutableListOf()}
                require(r.getInt(1)==chunks.size){"Trip rows are incomplete or reordered."}
                val part=r.getString(2);chars+=part.length;require(chars<=TRIP_LIMIT*2){"Shared trip is too large."};chunks+=part
            }
            val json=JSONObject(header.encode()).put("entries",JSONArray(packed.map{(key,chunks)->
                JSONObject(java.util.Base64.getDecoder().decode(chunks.joinToString("")).toString(Charsets.UTF_8)).also{require(it.getString("id")==key)}
            }))
            val remote=decodeTrip(json.toString())
            if(local==null)return remote
            val merged=remote.merge(local)
            require(merged.encode().toByteArray().size<=TRIP_LIMIT){"Combined trip exceeds the trip limit."}
            val ids=remote.entries.map{it.id}.toSet()
            val new=merged.entries.filter{it.id !in ids}
            if(new.isEmpty())return merged
            try {
                call("$docs/$id:batchUpdate","POST",obj("requests" to arr(obj("deleteNamedRange" to obj("namedRangeId" to revision)),
                    marker(revision(),tabId),append(rows(merged.copy(entries=new)),tabId))))
                return merged
            } catch(e:ApiError){if(e.status!=400 || it==2)throw e}
        }
        error("Trip changed during sync. Try again.")
    }
    fun invite(id:String,email:String) {
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()){"Enter the member's Google email address."}
        call("$drive/"+sheetIdFrom(id)+"/permissions?sendNotificationEmail=true","POST",obj("type" to "user","role" to "writer","emailAddress" to email.trim()))
    }
    fun shortcut(id:String,name:String) {
        val target=sheetIdFrom(id)
        
        // Drive doesn't support all shortcut fields as search terms; use our own stable property.
        val filter="trashed = false and 'me' in owners and appProperties has { key='taptransferShortcut' and value='$target' }"
        if(call(drive+"?fields=files(id)&q="+encode(filter)).getJSONArray("files").length()>0)return
        call(drive,"POST",obj("name" to name,"mimeType" to "application/vnd.google-apps.shortcut",
            "shortcutDetails" to obj("targetId" to target),"appProperties" to obj("taptransferShortcut" to target)))
    }
}
