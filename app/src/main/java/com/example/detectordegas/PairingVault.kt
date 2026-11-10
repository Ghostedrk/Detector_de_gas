package com.example.detectordegas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protege el código de enlace en el almacenamiento privado con Android Keystore. */
class PairingVault(context: Context) {
    private val prefs = context.getSharedPreferences("local_pairing", 0)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("detector_pairing_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("detector_pairing_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun save(owner: String, code: String) {
        val raw = LocalCrypto.unhex(code.filterNot { it.isWhitespace() }).also { require(it.size == 32) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(owner.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(owner, LocalCrypto.hex(cipher.iv + cipher.doFinal(raw))).apply()
    }
    fun load(owner: String): String? {
        val value = prefs.getString(owner, null) ?: return null
        val packet = LocalCrypto.unhex(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
        cipher.updateAAD(owner.toByteArray(Charsets.UTF_8))
        return LocalCrypto.hex(cipher.doFinal(packet.copyOfRange(12, packet.size)))
    }
}
