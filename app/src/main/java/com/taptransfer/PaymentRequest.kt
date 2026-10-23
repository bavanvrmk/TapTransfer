package com.taptransfer

import android.net.Uri
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

data class PaymentRequest(val id: String, val upi: String, val name: String, val amount: String, val note: String, val expires: Long) {
    fun validate(now: Long = System.currentTimeMillis()): PaymentRequest {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid request identifier" }
        require(upi.matches(Regex("[A-Za-z0-9._-]{2,128}@[A-Za-z0-9.-]{2,64}"))) { "Enter a valid UPI ID" }
        require(name.isNotBlank() && name.length <= 60 && name.none { it.isISOControl() }) { "Enter a name (up to 60 characters)" }
        require(note.length <= 80 && note.none { it.isISOControl() }) { "Note must be at most 80 characters" }
        require(amount.matches(Regex("[0-9]{1,6}(\\.[0-9]{1,2})?"))) { "Enter an amount with at most two decimals" }
        require(BigDecimal(amount) > BigDecimal.ZERO && BigDecimal(amount) <= BigDecimal("100000")) { "Amount must be between ₹0.01 and ₹1,00,000" }
        require(expires > now && expires <= now + 125000) { "Request expired. Ask for a new request." }
        return this
    }
    fun uri(): Uri = Uri.Builder().scheme("upi").authority("pay")
        .appendQueryParameter("pa", upi).appendQueryParameter("pn", name)
        .appendQueryParameter("am", amount).appendQueryParameter("cu", "INR")
        .appendQueryParameter("tn", note).build()
    fun json(): String = JSONObject().put("v", 1).put("id", id).put("upi", upi).put("name", name)
        .put("amount", amount).put("note", note).put("expires", expires).toString()
    companion object {
        fun create(upi: String, name: String, amount: String, note: String) =
            PaymentRequest(UUID.randomUUID().toString(), upi.trim(), name.trim(), amount.trim(), note.trim(), System.currentTimeMillis()+120000).validate()
        fun parse(text: String): PaymentRequest {
            require(text.toByteArray().size <= 2048) { "Request too large" }
            val j = JSONObject(text)
            require(j.getInt("v") == 1) { "Unsupported request version" }
            return PaymentRequest(j.getString("id"), j.getString("upi"), j.getString("name"), j.getString("amount"), j.getString("note"), j.getLong("expires")).validate()
        }
    }
}
