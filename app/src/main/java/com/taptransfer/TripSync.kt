package com.taptransfer

import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

object TripSync {
    private fun url(value:String):URI {
        val uri=URI(value.trim())
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null) { "Use an HTTPS trip server URL." }
        return uri
    }
    private data class Reply(val code:Int,val body:String,val tag:String?)
    private fun send(endpoint:String,token:String,method:String,body:String?=null,tag:String?=null):Reply {
        val c=url(endpoint).toURL().openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects=false;c.connectTimeout=10000;c.readTimeout=30000;c.requestMethod=method
            c.setRequestProperty("Authorization","Bearer "+token)
            if(tag!=null)c.setRequestProperty("If-Match",tag)
            if(body!=null) {
                val bytes=body.toByteArray()
                require(bytes.size<=TRIP_LIMIT){"Trip exceeds sync size limit."}
                c.doOutput=true;c.setFixedLengthStreamingMode(bytes.size);c.setRequestProperty("Content-Type","application/json")
                c.outputStream.use { it.write(bytes) }
            }
            val code=c.responseCode
            return Reply(code,if(code in 200..299)c.inputStream.bounded(TRIP_LIMIT).toString(Charsets.UTF_8) else "",c.getHeaderField("ETag"))
        } finally { c.disconnect() }
    }
    private fun parts(invite:String):Pair<String,String> {
        val u=url(invite)
        require(u.path.matches(Regex(".*/trips/[0-9a-f-]{36}")) && u.fragment.orEmpty().matches(Regex("[A-Za-z0-9_-]{40,64}"))){"Paste the complete private trip invite."}
        return invite.trim().substringBefore('#') to u.fragment
    }
    fun create(server:String,key:String,trip:Trip):String {
        val endpoint=url(server).toString().trimEnd('/')+"/trips"
        val r=send(endpoint,key,"POST",trip.encode())
        require(r.code==201){"Could not create shared trip (HTTP "+r.code+")."}
        val obj=JSONObject(r.body)
        require(obj.getString("id")==trip.id)
        return (endpoint+"/"+trip.id+"#"+obj.getString("token")).also{parts(it)}
    }
    fun sync(invite:String,local:Trip?=null):Trip {
        val (endpoint,token)=parts(invite)
        repeat(3) {
            val fetched=send(endpoint,token,"GET")
            require(fetched.code==200){"Could not read shared trip (HTTP "+fetched.code+")."}
            val remote=decodeTrip(fetched.body)
            require(endpoint.endsWith("/"+remote.id))
            if(local==null)return remote
            val merged=remote.merge(local)
            if(merged.entries.size==remote.entries.size)return remote
            val updated=send(endpoint,token,"PUT",merged.encode(),requireNotNull(fetched.tag))
            if(updated.code==200)return merged
            require(updated.code==409){"Could not upload entries (HTTP "+updated.code+")."}
        }
        error("Others are updating this trip. Tap Sync again.")
    }
}
