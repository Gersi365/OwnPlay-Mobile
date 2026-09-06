package app.ownplay.player.persistence.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreSensitiveValueStore(context: Context) : SensitiveValueStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val encryptionKey: SecretKey by lazy { getOrCreateKey() }

    override fun put(value: String): SensitiveValueRef = putAll(listOf(value)).single()

    override fun putAll(values: List<String>): List<SensitiveValueRef> {
        if (values.isEmpty()) return emptyList()

        val encryptedValues = values.map { value ->
            val ref = SensitiveValueRef(UUID.randomUUID().toString())
            val plaintext = value.toByteArray(StandardCharsets.UTF_8)
            val encrypted = try {
                SensitiveValueCrypto.encrypt(encryptionKey, plaintext)
            } finally {
                plaintext.fill(0)
            }
            ref to encode(encrypted)
        }

        val editor = preferences.edit()
        encryptedValues.forEach { (ref, encoded) ->
            editor.putString(storageKey(ref), encoded)
        }
        check(editor.commit()) { "Unable to persist encrypted sensitive values" }
        return encryptedValues.map { (ref, _) -> ref }
    }

    override fun get(ref: SensitiveValueRef): String? {
        val encoded = preferences.getString(storageKey(ref), null) ?: return null
        val encrypted = decode(encoded)
        val plaintext = SensitiveValueCrypto.decrypt(encryptionKey, encrypted)
        return try {
            String(plaintext, StandardCharsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun delete(ref: SensitiveValueRef) {
        deleteAll(listOf(ref))
    }

    override fun deleteAll(refs: Collection<SensitiveValueRef>) {
        if (refs.isEmpty()) return
        val editor = preferences.edit()
        refs.forEach { ref -> editor.remove(storageKey(ref)) }
        check(editor.commit()) { "Unable to delete encrypted sensitive values" }
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

    private fun storageKey(ref: SensitiveValueRef): String = "value.${ref.value}"

    private fun encode(payload: EncryptedSensitiveValue): String = buildString {
        append(STORAGE_VERSION)
        append(':')
        append(BASE64_ENCODER.encodeToString(payload.iv))
        append(':')
        append(BASE64_ENCODER.encodeToString(payload.ciphertext))
    }

    private fun decode(value: String): EncryptedSensitiveValue {
        val parts = value.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == STORAGE_VERSION) {
            "Unsupported sensitive-value payload format"
        }
        return EncryptedSensitiveValue(
            iv = BASE64_DECODER.decode(parts[1]),
            ciphertext = BASE64_DECODER.decode(parts[2]),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "ownplay_secure_values"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "ownplay.persistence.sensitive.v1"
        const val STORAGE_VERSION = "v1"
        val KEY_LOCK = Any()
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val BASE64_DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
