package app.ownplay.player.persistence.secure

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SensitiveValueCryptoTest {
    @Test
    fun cryptoRoundTripsOpaqueLocatorPayload() {
        val key = testKey()
        val plaintext = "https://example.test/list.m3u?token=fixture-secret".toByteArray()

        val encrypted = SensitiveValueCrypto.encrypt(key, plaintext)
        val decrypted = SensitiveValueCrypto.decrypt(key, encrypted)

        assertArrayEquals(plaintext, decrypted)
        assertFalse(encrypted.ciphertext.contentEquals(plaintext))
    }

    @Test
    fun cryptoRejectsTampering() {
        val key = testKey()
        val plaintext = "fixture-sensitive-value".toByteArray()
        val encrypted = SensitiveValueCrypto.encrypt(key, plaintext)
        val tampered = encrypted.copy(
            ciphertext = encrypted.ciphertext.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            },
        )

        assertThrows(GeneralSecurityException::class.java) {
            SensitiveValueCrypto.decrypt(key, tampered)
        }
    }

    @Test
    fun referenceStringRenderingIsOpaque() {
        val rendered = SensitiveValueRef("internal-reference-value").toString()

        assertFalse(rendered.contains("internal-reference-value"))
    }

    private fun testKey() = KeyGenerator.getInstance("AES").apply {
        init(256)
    }.generateKey()
}
