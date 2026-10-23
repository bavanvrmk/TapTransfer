package com.taptransfer

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow

object Session {
    @Volatile var armed = false
    @Volatile var request: PaymentRequest? = null
    val status = MutableStateFlow("Ready when you are")
}
class HelloService : HostApduService() {
    private var selected = false
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val c = commandApdu ?: return byteArrayOf(0x67, 0)
        val request = Session.request
        if (!Session.armed || request == null || request.expires <= System.currentTimeMillis()) return byteArrayOf(0x69, 0x85.toByte())
        if (c.contentEquals(Protocol.select) || c.contentEquals(Protocol.select.dropLast(1).toByteArray())) {
            selected = true
            return "TT1".toByteArray() + Protocol.ok
        }
        if (!selected || c.size != 5 || c[0] != 0x80.toByte() || c[1] != 0x10.toByte()) return byteArrayOf(0x6D, 0)
        val offset = ((c[2].toInt() and 255) shl 8) or (c[3].toInt() and 255)
        val data = request.json().toByteArray(Charsets.UTF_8)
        if (offset >= data.size) return byteArrayOf(0x6B, 0)
        val end = minOf(offset + 180, data.size)
        Session.status.value = "Request shared • payment is not yet verified"
        return data.copyOfRange(offset, end) + if (end == data.size) Protocol.ok else byteArrayOf(0x61, 0)
    }
    override fun onDeactivated(reason: Int) { selected = false }
}
