package com.example.detectordegas

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID

class SimulatedDetectorTest {
    @Test fun followsArduinoWarmupThresholdsAndManualTest() {
        var now = 0L
        val sim = SimulatedDetector { now }
        assertEquals("calentando", sim.read().state)
        assertEquals(SIMULATED_SOURCE, sim.read().source)
        now = 14_000; assertEquals(610, sim.read().value); assertEquals("alerta", sim.read().state)
        now = 18_000; assertEquals(470, sim.read().value)
        assertEquals("La histéresis mantiene la alerta entre 450 y 500", "alerta", sim.read().state)
        now = 20_000; assertEquals(430, sim.read().value); assertEquals("sin_alerta", sim.read().state)
        sim.triggerTest(); assertEquals("prueba", sim.read().state)
        now = 23_001; assertEquals("sin_alerta", sim.read().state)
    }
    @Test fun alertHasPriorityOverManualTest() {
        var now = 0L
        val sim = SimulatedDetector { now }
        now = 14_000
        sim.triggerTest()
        assertEquals("alerta", sim.read().state)
    }
    @Test fun simulatedReadingsAreValidSensorPayloads() {
        var now = 0L
        val sim = SimulatedDetector { now }
        repeat(30) {
            val r = sim.read()
            assertEquals(r.value, Reading.parse(r.json()).value)
            now += 1000
        }
    }
}

class EspAuthTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = "00112233445566778899aabbccddeeff"

    @Test fun matchesReferenceVectorsUsedByFirmware() {
        // Vectores calculados de forma independiente con Python (hmac + hashlib).
        assertEquals("66ecdcf85260eaaba753d7792fed6891b7085c29b774f9034dc924b664b898ac",
            EspAuth.request(key, "GET", "/estado", nonce))
        assertEquals("61de7be5f6fc078b3e6ad10e748a8ba13c5495172f99337a210929abb200fd11",
            EspAuth.response(key, nonce, """{"resultado":"prueba_enviada"}"""))
    }
    @Test fun rejectsTamperedBodyWrongNonceWrongKeyOrMissingSignature() {
        val body = """{"lectura":620,"estado":"alerta","conexion_arduino":true,"unidad":"ADC"}"""
        val sig = EspAuth.response(key, nonce, body)
        assertTrue(EspAuth.verifyResponse(key, nonce, body, sig))
        assertFalse(EspAuth.verifyResponse(key, nonce, body.replace("620", "120"), sig))
        assertFalse(EspAuth.verifyResponse(key, "ffeeddccbbaa99887766554433221100", body, sig))
        assertFalse(EspAuth.verifyResponse(LocalCrypto.randomBytes(32), nonce, body, sig))
        assertFalse(EspAuth.verifyResponse(key, nonce, body, null))
    }
    @Test fun deviceKeyMustBe64HexCharacters() {
        assertEquals(32, LocalCrypto.deviceKey(LocalCrypto.hex(LocalCrypto.randomBytes(32))).size)
        assertThrows(IllegalArgumentException::class.java) { LocalCrypto.deviceKey("PEGA_AQUI_LA_CLAVE_DE_64_CARACTERES") }
        assertThrows(IllegalArgumentException::class.java) { LocalCrypto.deviceKey("z".repeat(64)) }
    }
}

/** ESP32 falso en la red privada del equipo, con el mismo protocolo que firmware/ESP32/ESP32.ino. */
class EspClientTest {
    private fun privateAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && runCatching { privateIp(it.hostAddress!!) }.isSuccess }
        ?.hostAddress

    private fun fakeEsp(ip: String, key: ByteArray, onTest: () -> Unit): HttpServer {
        var challenge = ""
        return HttpServer.create(InetSocketAddress(ip, 80), 0).apply {
            fun authorized(ex: com.sun.net.httpserver.HttpExchange, method: String, path: String): String? {
                val nonce = ex.requestHeaders.getFirst("X-Nonce") ?: return null
                val sig = ex.requestHeaders.getFirst("X-Firma") ?: return null
                return nonce.takeIf { ex.requestMethod == method && sig == EspAuth.request(key, method, path, nonce) }
            }
            fun send(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String, nonce: String? = null) {
                if (nonce != null) ex.responseHeaders.add("X-Firma", EspAuth.response(key, nonce, body))
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            createContext("/estado") { ex ->
                val nonce = authorized(ex, "GET", "/estado")
                if (nonce == null) send(ex, 401, "{}")
                else send(ex, 200, """{"lectura":620,"estado":"alerta","conexion_arduino":true,"unidad":"ADC"}""", nonce)
            }
            createContext("/desafio") { ex ->
                challenge = LocalCrypto.hex(LocalCrypto.randomBytes(16))
                send(ex, 200, """{"desafio":"$challenge"}""")
            }
            createContext("/prueba") { ex ->
                val nonce = authorized(ex, "POST", "/prueba")
                val valid = nonce != null && nonce == challenge
                challenge = ""
                if (!valid) send(ex, 401, "{}") else { onTest(); send(ex, 200, """{"resultado":"prueba_enviada"}""", nonce) }
            }
            start()
        }
    }

    @Test fun signedStatusAndControlAgainstFakeEsp32() = runBlocking {
        val ip = privateAddress()
        assumeTrue("El equipo no tiene una IPv4 privada disponible", ip != null)
        val key = LocalCrypto.randomBytes(32)
        var tests = 0
        val server = try { fakeEsp(ip!!, key) { tests++ } } catch (e: Exception) { assumeTrue("Puerto 80 no disponible: ${e.message}", false); return@runBlocking }
        try {
            assertEquals(620, Reading.parse(EspClient.status(ip, key)).value)
            EspClient.test(ip, key)
            assertEquals(1, tests)
            val wrongKey = runCatching { EspClient.status(ip, LocalCrypto.randomBytes(32)) }
            assertTrue(wrongKey.exceptionOrNull()?.message.orEmpty().contains("firma"))
        } finally { server.stop(0) }
    }
}

class RemoteControlTest {
    private fun request(op: String, device: String) = JSONObject().put("id", UUID.randomUUID().toString())
        .put("op", op).put("sender", device).put("device", "Receptor")

    @Test fun receiverTestRequestIsQueuedForPublisherOnce() {
        val hub = LocalHub()
        val phone = UUID.randomUUID().toString()
        val response = hub.handle(request("test", phone))
        assertTrue(response.getString("command").contains("Receptor"))
        assertThrows(IllegalArgumentException::class.java) { hub.handle(request("test", phone)) }
        assertEquals(listOf("Receptor"), hub.takeTests())
        assertTrue(hub.takeTests().isEmpty())
        hub.setCommand("Prueba de alarma enviada")
        assertEquals("Prueba de alarma enviada", hub.handle(request("status", phone)).getString("command"))
    }
}
