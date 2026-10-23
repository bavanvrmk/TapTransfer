package com.taptransfer
import org.junit.Assert.*
import org.junit.Test

class PaymentRequestTest {
    private val now = 1700000000000L
    private fun request(amount: String = "10.50", upi: String = "alex@bank", expires: Long = now+60000) =
        PaymentRequest("12345678-1234-1234-1234-123456789012",upi,"Alex",amount,"Lunch",expires)
    @Test fun decimalAmountAccepted() { assertEquals("10.50",request().validate(now).amount) }
    @Test(expected=IllegalArgumentException::class) fun negativeRejected() { request("-1").validate(now) }
    @Test(expected=IllegalArgumentException::class) fun zeroRejected() { request("0").validate(now) }
    @Test(expected=IllegalArgumentException::class) fun precisionRejected() { request("1.001").validate(now) }
    @Test(expected=IllegalArgumentException::class) fun largeAmountRejected() { request("100001").validate(now) }
    @Test(expected=IllegalArgumentException::class) fun queryInjectionRejected() { request(upi="alex@bank&am=999").validate(now) }
    @Test(expected=IllegalArgumentException::class) fun expiredRejected() { request(expires=now).validate(now) }
    @Test(expected=IllegalArgumentException::class) fun excessiveExpiryRejected() { request(expires=now+300000).validate(now) }
}
