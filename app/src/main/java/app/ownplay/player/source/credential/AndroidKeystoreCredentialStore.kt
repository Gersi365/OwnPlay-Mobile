package app.ownplay.player.source.credential

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.ownplay.player.source.CredentialRef
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun put(credentials: XtreamCredentials): CredentialRef {
        val ref = CredentialRef(UUID.randomUUID().toString())
        val plaintext = XtreamCredentialsCodec.encode(credentials)
        val encrypted = try {
            CredentialCrypto.encrypt(getOrCreateKey(), plaintext)
        } finally {
            plaintext.fill(0)
        }

        check(
            preferences.edit()
                .putString(preferenceKey(ref), encodePayload(encrypted))
                .commit(),
        ) { "Unable to persist encrypted credentials" }

        return ref
    }

    override fun get(ref: CredentialRef): XtreamCredentials? {
        val encoded = preferences.getString(preferenceKey(ref), null) ?: return null
        val encrypted = decodePayload(encoded)
        val plaintext = CredentialCrypto.decrypt(getOrCreateKey(), encrypted)
        return try {
            XtreamCredentialsCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun delete(ref: CredentialRef) {
        check(preferences.edit().remove(preferenceKey(ref)).commit()) {
            "Unable to delete encrypted credentials"
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun preferenceKey(ref: CredentialRef): String = "credential.${ref.value}"

    private fun encodePayload(payload: EncryptedCredentialPayload): String = buildString {
        append(STORAGE_VERSION)
        append(':')
        append(BASE64_ENCODER.encodeToString(payload.iv))
        append(':')
        append(BASE64_ENCODER.encodeToString(payload.ciphertext))
    }

    private fun decodePayload(value: String): EncryptedCredentialPayload {
        val parts = value.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == STORAGE_VERSION) {
            "Unsupported credential payload format"
        }
        return EncryptedCredentialPayload(
            iv = BASE64_DECODER.decode(parts[1]),
            ciphertext = BASE64_DECODER.decode(parts[2]),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "ownplay_secure_credentials"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ownplay.xtream.credentials.v1"
        const val STORAGE_VERSION = "v1"
        val KEY_LOCK = Any()
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val BASE64_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
