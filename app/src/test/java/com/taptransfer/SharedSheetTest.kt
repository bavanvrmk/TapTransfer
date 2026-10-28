package com.taptransfer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SharedSheetTest {
    private val id="abcD12345678901234567890"
    private val base=Trip(tripId(),"Trip",listOf("Kai","Alex"),"Kai")
    private fun expense(who:String)=TripEntry(tripId(),1,"Expense","Lunch","Cash",mapOf(who to 300L),mapOf("Kai" to 300L),by=who)
    @Test fun twoWritersRetryAndNoDuplicateAfterReopen() {
        var revision="tt_initial"
        val rows=JSONArray().put(JSONArray().put(base.id).put("0").put(base.encode()))
        val other=expense("Kai")
        fun putEntry(e:TripEntry) {
            val packed=JSONObject(base.copy(entries=listOf(e)).encode()).getJSONArray("entries").getJSONObject(0).toString()
            rows.put(JSONArray().put(e.id).put("0").put(java.util.Base64.getEncoder().encodeToString(packed.toByteArray())))
        }
        var attempts=0
        val api=SharedSheet("test") { url,method,body->
            when {
                url.contains("fields=namedRanges") -> JSONObject().put("namedRanges",JSONArray().put(JSONObject().put("name",revision).put("namedRangeId",revision).put("range",JSONObject().put("sheetId",22))))
                url.contains("/values/")-> JSONObject().put("values",rows)
                method=="POST" -> {
                    attempts++
                    if(attempts==1) { putEntry(other);revision="tt_concurrent";throw SharedSheet.ApiError(400,"stale revision") }
                    val ops=body!!.getJSONArray("requests")
                    assertEquals(revision,ops.getJSONObject(0).getJSONObject("deleteNamedRange").getString("namedRangeId"))
                    revision=ops.getJSONObject(1).getJSONObject("addNamedRange").getJSONObject("namedRange").getString("name")
                    val append=ops.getJSONObject(2).getJSONObject("appendCells")
                    assertEquals(22,append.getInt("sheetId"))
                    val added=append.getJSONArray("rows")
                    for(i in 0 until added.length()){
                        val v=added.getJSONObject(i).getJSONArray("values")
                        rows.put(JSONArray((4..6).map{v.getJSONObject(it).getJSONObject("userEnteredValue").getString("stringValue")}))
                    }
                    JSONObject()
                }
                else->error("Unexpected request")
            }
        }
        val local=base.copy(entries=listOf(expense("Alex")))
        val merged=api.sync(id,local)
        assertEquals(600L,merged.totals().spent)
        assertEquals(2,api.sync(sheetLink(id),local).entries.size)
        assertEquals(2,attempts)
    }
    @Test fun initializesNativeSheetWithTripAndExistingExpenses() {
        var seeded=false
        val api=SharedSheet("test"){ url,method,body->
            when {
                url.contains("fields=files")->JSONObject().put("files",JSONArray())
                method=="GET"->JSONObject("""{"sheets":[{"properties":{"sheetId":81,"title":"Sheet1"}}]}""")
                url.endsWith(":batchUpdate")->{
                    val requests=body!!.getJSONArray("requests")
                    val append=(0 until requests.length()).map{requests.getJSONObject(it)}.single{it.has("appendCells")}.getJSONObject("appendCells")
                    assertEquals(81,append.getInt("sheetId"))
                    val rows=append.getJSONArray("rows")
                    assertEquals(3,rows.length())
                    val metadata=rows.getJSONObject(1).getJSONArray("values").getJSONObject(6).getJSONObject("userEnteredValue").getString("stringValue")
                    assertEquals(base,decodeTrip(metadata))
                    seeded=true;JSONObject()
                }
                else->{
                    assertEquals("application/vnd.google-apps.spreadsheet",body!!.getString("mimeType"))
                    assertEquals(base.id,body.getJSONObject("appProperties").getString("taptransferTrip"))
                    JSONObject().put("id",id)
                }
            }
        }
        assertEquals(id,api.create(base.copy(entries=listOf(expense("Alex")))))
        assertTrue(seeded)
    }
    @Test fun inviteAndShortcutUseOneTarget() {
        var shortcuts=0
        var invitations=0
        val api=SharedSheet("test") { url,method,body->
            when {
                url.contains("/permissions?")->{
                    assertTrue(url.contains("/$id/permissions?sendNotificationEmail=true"))
                    assertEquals("writer",body!!.getString("role"))
                    assertEquals("member@example.org",body.getString("emailAddress"))
                    invitations++;JSONObject()
                }
                method=="GET"->JSONObject().put("files",if(shortcuts==0) JSONArray() else JSONArray().put(JSONObject().put("id","shortcut")))
                else->{
                    assertEquals("application/vnd.google-apps.shortcut",body!!.getString("mimeType"))
                    assertEquals(id,body.getJSONObject("shortcutDetails").getString("targetId"))
                    shortcuts++;JSONObject()
                }
            }
        }
        api.invite(id,"member@example.org")
        repeat(2){api.shortcut(id,base.name)}
        assertEquals(1,shortcuts)
        assertEquals(1,invitations)
    }
    @Test fun rejectsUnrelatedLinks(){assertThrows(IllegalArgumentException::class.java){sheetIdFrom("https://example.org/spreadsheets/d/"+id)}}
}
