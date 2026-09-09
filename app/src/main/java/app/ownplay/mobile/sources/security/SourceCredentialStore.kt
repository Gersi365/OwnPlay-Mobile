package app.ownplay.mobile.sources.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.ownplay.mobile.sources.XtreamCredentials
import app.ownplay.mobile.sources.stableSourceKey
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface SourceCredentialStore {
    fun putXtream(sourceId: String, credentials: XtreamCredentials)
    fun getXtream(sourceId: String): XtreamCredentials?
    fun remove(sourceId: String)
}

class AndroidKeystoreSourceCredentialStore(context: Context) : SourceCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun putXtream(sourceId: String, credentials: XtreamCredentials) {
        val payload = buildJsonObject {
            put("username", credentials.username)
            put("password", credentials.password)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload)
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(preferenceKey(sourceId), encoded).commit())
    }

    override fun getXtream(sourceId: String): XtreamCredentials? {
        val encoded = preferences.getString(preferenceKey(sourceId), null) ?: return null
        val separator = encoded.indexOf(':')
        if (separator <= 0 || separator == encoded.lastIndex) return null
        return runCatching {
            val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
            val ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val cleartext = cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
            val objectValue = json.parseToJsonElement(cleartext).jsonObject
            val username = objectValue["username"]?.jsonPrimitive?.contentOrNull ?: return null
            val password = objectValue["password"]?.jsonPrimitive?.contentOrNull ?: return null
            XtreamCredentials(username = username, password = password)
        }.getOrNull()
    }

    override fun remove(sourceId: String) {
        preferences.edit().remove(preferenceKey(sourceId)).apply()
    }

    private fun preferenceKey(sourceId: String): String = "credential_${stableSourceKey(sourceId)}"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES = "ownplay_v2_source_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ownplay_mobile_source_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
