package com.taptransfer
import org.junit.Assert.*
import org.junit.Test
class ProtocolTest {
 @Test fun helloRoundTrip() { assertEquals("hello", Protocol.decode(Protocol.respond(Protocol.select, true))) }
 @Test fun disarmedRejects() { assertArrayEquals(byteArrayOf(0x69, 0x85.toByte()), Protocol.respond(Protocol.select, false)) }
 @Test fun malformedRejects() { assertArrayEquals(byteArrayOf(0x6D, 0), Protocol.respond(byteArrayOf(0), true)) }
 @Test(expected = IllegalArgumentException::class) fun truncatedResponseRejects() { Protocol.decode(byteArrayOf(0x90.toByte())) }
}