package com.example.detectordegas
import org.junit.Assert.*
import org.junit.Test
class EndpointValidationTest {
    @Test fun acceptsOnlyPrivateLiteralAddresses() {
        assertEquals("http://192.168.1.50/estado", espUrl("192.168.1.50"))
        assertEquals("10.0.0.2", privateIp("10.0.0.2"))
        assertEquals("172.31.1.2", privateIp("172.31.1.2"))
    }
    @Test fun rejectsPublicHostnamesAndMalformedSensorAddresses() {
        listOf("8.8.8.8", "127.0.0.1", "169.254.1.1", "172.32.0.1", "192.168.1.256", "192.168.01.1",
            "https://192.168.1.5", "192.168.1.5@evil.com", "example.com", "192.168.1.2/estado").forEach { value ->
            assertThrows("Must reject $value", IllegalArgumentException::class.java) { privateIp(value) }
        }
    }
}
