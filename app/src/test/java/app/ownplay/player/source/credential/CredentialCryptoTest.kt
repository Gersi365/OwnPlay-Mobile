package app.ownplay.player.source.credential

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialCryptoTest {
    @Test
    fun credentialCodec_roundTripsUnicodeValues() {
        val original = XtreamCredentials(
            username = "përdorues-测试",
            password = "sëcret-🔒-value",
        )

        val encoded = XtreamCredentialsCodec.encode(original)
        val decoded = XtreamCredentialsCodec.decode(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun aesGcm_roundTripsCredentialPayload() {
        val key = testKey()
        val plaintext = XtreamCredentialsCodec.encode(
            XtreamCredentials("user@example.com", "correct horse battery staple"),
        )

        val encrypted = CredentialCrypto.encrypt(key, plaintext)
        val decrypted = CredentialCrypto.decrypt(key, encrypted)

        assertArrayEquals(plaintext, decrypted)
        assertFalse(encrypted.ciphertext.contentEquals(plaintext))
    }

    @Test
    fun aesGcm_rejectsTamperedCiphertext() {
        val key = testKey()
        val plaintext = XtreamCredentialsCodec.encode(XtreamCredentials("user", "password"))
        val encrypted = CredentialCrypto.encrypt(key, plaintext)
        val tampered = encrypted.copy(
            ciphertext = encrypted.ciphertext.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            },
        )

        assertThrows(GeneralSecurityException::class.java) {
            CredentialCrypto.decrypt(key, tampered)
        }
    }

    private fun testKey() = KeyGenerator.getInstance("AES").apply {
        init(256)
    }.generateKey()
}
