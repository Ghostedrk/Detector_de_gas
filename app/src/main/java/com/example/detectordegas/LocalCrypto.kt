package com.example.detectordegas

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

const val ROLE_ADMIN = "administrador"
const val ROLE_OPERATOR = "operador"

/** Usuario local. El primer usuario del teléfono es administrador; los siguientes, operadores. */
data class LocalUser(val uid: String, val username: String, val role: String = ROLE_OPERATOR)

object PasswordHash {
    const val ITERATIONS = 210000
    fun derive(password: String, salt: ByteArray, algorithm: String): ByteArray {
        require(algorithm in setOf("PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1"))
        require(salt.size >= 16)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

/** Mensajes autenticados y cifrados entre teléfonos. La dirección forma parte del AAD. */
object LocalCrypto {
    private val random = SecureRandom()
    fun randomBytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]*"))) { "Código hexadecimal inválido." }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun pairingKey(code: String) = key32(code, "Copia los 64 caracteres del código de enlace del emisor.")
    fun deviceKey(code: String) = key32(code, "La clave del ESP32 debe tener 64 caracteres hexadecimales.")
    private fun key32(code: String, message: String): ByteArray {
        val normalized = code.filterNot { it.isWhitespace() }
        require(normalized.length == 64 && normalized.matches(Regex("[0-9a-fA-F]*"))) { message }
        return unhex(normalized)
    }
    fun hmacHex(key: ByteArray, message: String): String {
        require(key.size == 32)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }
    fun seal(key: ByteArray, plain: ByteArray, direction: String): ByteArray {
        require(key.size == 32)
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("detector-local-v1:$direction".toByteArray(Charsets.UTF_8))
        return iv + cipher.doFinal(plain)
    }
    fun open(key: ByteArray, packet: ByteArray, direction: String): ByteArray {
        require(key.size == 32 && packet.size in 28..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
        cipher.updateAAD("detector-local-v1:$direction".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(packet.copyOfRange(12, packet.size))
    }
}
