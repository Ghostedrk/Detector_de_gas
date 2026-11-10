package com.example.detectordegas

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.UUID

class LocalSecurityTest {
    @Test fun messagesRoundTripWithDifferentRandomIvs() {
        val key = LocalCrypto.randomBytes(32)
        val plain = "lectura privada".toByteArray()
        val a = LocalCrypto.seal(key, plain, "request")
        val b = LocalCrypto.seal(key, plain, "request")
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain, LocalCrypto.open(key, a, "request"))
    }
    @Test fun rejectsTamperedWrongKeyAndReflectedMessages() {
        val key = LocalCrypto.randomBytes(32)
        val packet = LocalCrypto.seal(key, "estado".toByteArray(), "request")
        val tampered = packet.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { LocalCrypto.open(key, tampered, "request") }
        assertThrows(Exception::class.java) { LocalCrypto.open(LocalCrypto.randomBytes(32), packet, "request") }
        assertThrows(Exception::class.java) { LocalCrypto.open(key, packet, "response") }
    }
    @Test fun pairingCodeRequiresFullRandomKey() {
        assertEquals(32, LocalCrypto.pairingKey(LocalCrypto.hex(LocalCrypto.randomBytes(32))).size)
        assertThrows(IllegalArgumentException::class.java) { LocalCrypto.pairingKey("123456") }
    }
    @Test fun passwordHashesDependOnPasswordAndSalt() {
        val salt = LocalCrypto.randomBytes(16)
        val good = PasswordHash.derive("clave-de-prueba", salt, "PBKDF2WithHmacSHA256")
        assertEquals(32, good.size)
        assertArrayEquals(good, PasswordHash.derive("clave-de-prueba", salt, "PBKDF2WithHmacSHA256"))
        assertFalse(good.contentEquals(PasswordHash.derive("otra-clave", salt, "PBKDF2WithHmacSHA256")))
        assertFalse(good.contentEquals(PasswordHash.derive("clave-de-prueba", LocalCrypto.randomBytes(16), "PBKDF2WithHmacSHA256")))
    }
    @Test fun legacyPasswordAlgorithmIsAvailable() {
        assertEquals(32, PasswordHash.derive("clave-de-prueba", LocalCrypto.randomBytes(16), "PBKDF2WithHmacSHA1").size)
    }
}

class LocalPeerTest {
    private fun request(op: String, device: String, reviewId: String = UUID.randomUUID().toString()) =
        JSONObject().put("id", UUID.randomUUID().toString()).put("op", op)
            .put("sender", device).put("device", "Teléfono de prueba").put("reviewId", reviewId)
    private fun call(server: LocalPeerServer, key: ByteArray, request: JSONObject): JSONObject =
        Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
            socket.soTimeout = 3000
            val challenge = ByteArray(32).also { DataInputStream(socket.getInputStream()).readFully(it) }
            request.put("challenge", LocalCrypto.hex(challenge))
            writeFrame(socket, LocalCrypto.seal(key, request.toString().toByteArray(), "request"))
            val result = JSONObject(LocalCrypto.open(key, readFrame(socket), "response").toString(Charsets.UTF_8))
            assertEquals(request.getString("id"), result.getString("id"))
            result
        }
    @Test fun exchangesReadingsAndReviewAcknowledgementsOverLocalTcp() {
        val key = LocalCrypto.randomBytes(32)
        val hub = LocalHub()
        hub.update(Reading(620, "alerta", true))
        val server = LocalPeerServer(key, hub, 0, InetAddress.getLoopbackAddress())
        server.start()
        try {
            val phoneA = UUID.randomUUID().toString()
            val phoneB = UUID.randomUUID().toString()
            val review = UUID.randomUUID().toString()
            val created = call(server, key, request("request", phoneA, review))
            assertEquals(620, created.getJSONObject("reading").getInt("lectura"))
            assertEquals(1, created.getJSONArray("reviews").length())
            val ack = call(server, key, request("ack", phoneB, review))
            assertTrue(ack.getJSONArray("reviews").getJSONObject(0).getString("ack").isNotEmpty())
        } finally { server.stop() }
    }
    @Test fun wrongPairingKeyAndOldChallengeCannotReadTheServer() {
        val key = LocalCrypto.randomBytes(32)
        val server = LocalPeerServer(key, LocalHub(), 0, InetAddress.getLoopbackAddress())
        server.start()
        try {
            assertThrows(Exception::class.java) { call(server, LocalCrypto.randomBytes(32), request("status", UUID.randomUUID().toString())) }
            Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
                socket.soTimeout = 3000
                DataInputStream(socket.getInputStream()).readFully(ByteArray(32))
                val wrongChallenge = request("status", UUID.randomUUID().toString()).put("challenge", LocalCrypto.hex(LocalCrypto.randomBytes(32)))
                writeFrame(socket, LocalCrypto.seal(key, wrongChallenge.toString().toByteArray(), "request"))
                assertThrows(Exception::class.java) { readFrame(socket) }
            }
            assertTrue(call(server, key, request("status", UUID.randomUUID().toString())).isNull("reading"))
        } finally { server.stop() }
    }
    @Test fun cannotAcknowledgeOwnRequestOrDuplicateAnOperation() {
        val hub = LocalHub()
        val phone = UUID.randomUUID().toString()
        val review = UUID.randomUUID().toString()
        val initial = request("request", phone, review)
        hub.handle(initial)
        assertThrows(IllegalArgumentException::class.java) { hub.handle(initial) }
        assertThrows(IllegalArgumentException::class.java) { hub.handle(request("ack", phone, review)) }
    }
}
