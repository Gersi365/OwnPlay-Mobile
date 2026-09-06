package app.ownplay.player.persistence.secure

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedSensitiveValue(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal object SensitiveValueCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private val aad = "OwnPlay:SensitiveValue:v1".toByteArray(StandardCharsets.UTF_8)

    @Throws(GeneralSecurityException::class)
    fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
    ): EncryptedSensitiveValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return EncryptedSensitiveValue(
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(
        key: SecretKey,
        payload: EncryptedSensitiveValue,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, payload.iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(payload.ciphertext)
    }
}
