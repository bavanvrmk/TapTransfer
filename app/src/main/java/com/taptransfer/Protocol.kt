package com.taptransfer

object Protocol {
    val select = byteArrayOf(0, 0xA4.toByte(), 4, 0, 7, 0xF0.toByte(), 1, 2, 3, 4, 5, 6, 0)
    val ok = byteArrayOf(0x90.toByte(), 0)
    fun respond(command: ByteArray, armed: Boolean): ByteArray {
        if (!armed) return byteArrayOf(0x69, 0x85.toByte())
        val valid = command.contentEquals(select) || command.contentEquals(select.dropLast(1).toByteArray())
        return if (valid) "hello".toByteArray(Charsets.UTF_8) + ok else byteArrayOf(0x6D, 0)
    }
    fun decode(response: ByteArray): String {
        require(response.size >= 2 && response.takeLast(2).toByteArray().contentEquals(ok)) { "Peer rejected the command; select Receive on the other phone." }
        return response.dropLast(2).toByteArray().toString(Charsets.UTF_8).also { require(it == "hello") { "Unexpected payload" } }
    }
}