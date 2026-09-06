package app.ownplay.player.source.credential

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedCredentialPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal object CredentialCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private val aad = "OwnPlay:XtreamCredentials:v1".toByteArray(StandardCharsets.UTF_8)

    @Throws(GeneralSecurityException::class)
    fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
    ): EncryptedCredentialPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return EncryptedCredentialPayload(
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(
        key: SecretKey,
        payload: EncryptedCredentialPayload,
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

internal object XtreamCredentialsCodec {
    private const val MAX_FIELD_BYTES = 16 * 1024

    fun encode(credentials: XtreamCredentials): ByteArray {
        val username = credentials.username.toByteArray(StandardCharsets.UTF_8)
        val password = credentials.password.toByteArray(StandardCharsets.UTF_8)
        try {
            require(username.size <= MAX_FIELD_BYTES) { "Credential username is too large" }
            require(password.size <= MAX_FIELD_BYTES) { "Credential password is too large" }

            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(username.size)
                    output.write(username)
                    output.writeInt(password.size)
                    output.write(password)
                }
                bytes.toByteArray()
            }
        } finally {
            username.fill(0)
            password.fill(0)
        }
    }

    fun decode(payload: ByteArray): XtreamCredentials =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val username = input.readBoundedBytes()
            try {
                val password = input.readBoundedBytes()
                try {
                    require(input.available() == 0) { "Credential payload has trailing data" }
                    XtreamCredentials(
                        username = String(username, StandardCharsets.UTF_8),
                        password = String(password, StandardCharsets.UTF_8),
                    )
                } finally {
                    password.fill(0)
                }
            } finally {
                username.fill(0)
            }
        }

    private fun DataInputStream.readBoundedBytes(): ByteArray {
        val length = readInt()
        require(length in 0..MAX_FIELD_BYTES) { "Credential field length is invalid" }
        return ByteArray(length).also(::readFully)
    }
}
