package app.ownplay.mobile.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.ownplay.mobile.sources.domain.SourceId
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class KeystoreCredentialStore(
    context: Context,
) : CredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override suspend fun put(
        sourceId: SourceId,
        secret: SourceSecret,
    ) = withContext(Dispatchers.IO) {
        val payload = encode(secret)
        val encrypted = encrypt(sourceId, payload)
        val saved = preferences.edit()
            .putString(storageKey(sourceId), encrypted)
            .commit()
        if (!saved) {
            throw CredentialStoreException("Credential storage commit failed")
        }
    }

    override suspend fun get(
        sourceId: SourceId,
    ): SourceSecret? = withContext(Dispatchers.IO) {
        val encrypted = preferences.getString(storageKey(sourceId), null)
            ?: return@withContext null
        val payload = decrypt(sourceId, encrypted)
        decode(payload)
    }

    override suspend fun delete(
        sourceId: SourceId,
    ) = withContext(Dispatchers.IO) {
        val removed = preferences.edit()
            .remove(storageKey(sourceId))
            .commit()
        if (!removed) {
            throw CredentialStoreException("Credential deletion commit failed")
        }
    }

    private fun encrypt(
        sourceId: SourceId,
        payload: ByteArray,
    ): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            cipher.updateAAD(sourceId.value.toByteArray(StandardCharsets.UTF_8))
            val ciphertext = cipher.doFinal(payload)
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val body = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            return "$FORMAT_VERSION:$iv:$body"
        } catch (error: Exception) {
            throw CredentialStoreException("Credential encryption failed", error)
        }
    }

    private fun decrypt(
        sourceId: SourceId,
        encoded: String,
    ): ByteArray {
        try {
            if (encoded.length > MAX_STORED_VALUE_LENGTH) {
                throw CredentialStoreException("Credential storage value is invalid")
            }

            val parts = encoded.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != FORMAT_VERSION) {
                throw CredentialStoreException("Unsupported credential storage format")
            }

            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) {
                throw CredentialStoreException("Credential storage value is invalid")
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(sourceId.value.toByteArray(StandardCharsets.UTF_8))
            return cipher.doFinal(ciphertext)
        } catch (error: CredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStoreException("Credential decryption failed", error)
        }
    }

    private fun encode(secret: SourceSecret): ByteArray {
        val json = JSONObject()
        when (secret) {
            is SourceSecret.Xtream -> {
                json.put("kind", "XTREAM")
                json.put("username", secret.username)
                json.put("password", secret.password)
            }

            is SourceSecret.M3uRemote -> {
                json.put("kind", "M3U_REMOTE")
                json.put("playlistUrl", secret.playlistUrl)
                if (secret.epgUrl != null) {
                    json.put("epgUrl", secret.epgUrl)
                }
            }
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(payload: ByteArray): SourceSecret {
        try {
            val json = JSONObject(String(payload, StandardCharsets.UTF_8))
            return when (json.getString("kind")) {
                "XTREAM" -> SourceSecret.Xtream(
                    username = json.getString("username"),
                    password = json.getString("password"),
                )

                "M3U_REMOTE" -> SourceSecret.M3uRemote(
                    playlistUrl = json.getString("playlistUrl"),
                    epgUrl = json.optString("epgUrl").takeIf(String::isNotBlank),
                )

                else -> throw CredentialStoreException("Unsupported credential secret kind")
            }
        } catch (error: CredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStoreException("Credential payload decode failed", error)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(KEY_SIZE_BITS)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
    }

    private fun storageKey(sourceId: SourceId): String =
        "source.${sourceId.value}"

    private companion object {
        const val PREFERENCES_NAME = "ownplay_secure_source_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ownplay.source.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val GCM_IV_BYTES = 12
        const val FORMAT_VERSION = "v1"
        const val MAX_STORED_VALUE_LENGTH = 128 * 1024
        val KEY_LOCK = Any()
    }
}
