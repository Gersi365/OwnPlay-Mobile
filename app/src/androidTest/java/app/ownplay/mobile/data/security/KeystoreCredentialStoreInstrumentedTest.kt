package app.ownplay.mobile.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ownplay.mobile.sources.domain.SourceId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreCredentialStoreInstrumentedTest {
    @Test
    fun xtreamCredentialsAreEncryptedAtRestAndSurviveStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceId = SourceId("security-test-${UUID.randomUUID()}")
        val username = "security-user-${UUID.randomUUID()}"
        val password = "security-password-${UUID.randomUUID()}"
        val store = KeystoreCredentialStore(context)

        try {
            store.put(sourceId, SourceSecret.Xtream(username, password))

            val encoded = preferences(context).getString(storageKey(sourceId), null)
            assertNotNull(encoded)
            assertFalse(encoded!!.contains(username))
            assertFalse(encoded.contains(password))

            val restored = KeystoreCredentialStore(context).get(sourceId) as SourceSecret.Xtream
            assertEquals(username, restored.username)
            assertEquals(password, restored.password)
        } finally {
            store.delete(sourceId)
        }
    }

    @Test
    fun m3uSecretUrlsAreEncryptedAtRest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceId = SourceId("security-m3u-${UUID.randomUUID()}")
        val playlistToken = "playlist-${UUID.randomUUID()}"
        val epgToken = "epg-${UUID.randomUUID()}"
        val playlistUrl = "https://media.example/list.m3u?token=$playlistToken"
        val epgUrl = "https://media.example/epg.xml?token=$epgToken"
        val store = KeystoreCredentialStore(context)

        try {
            store.put(sourceId, SourceSecret.M3uRemote(playlistUrl, epgUrl))

            val encoded = preferences(context).getString(storageKey(sourceId), null)
            assertNotNull(encoded)
            assertFalse(encoded!!.contains(playlistToken))
            assertFalse(encoded.contains(epgToken))

            val restored = store.get(sourceId) as SourceSecret.M3uRemote
            assertEquals(playlistUrl, restored.playlistUrl)
            assertEquals(epgUrl, restored.epgUrl)
        } finally {
            store.delete(sourceId)
        }
    }

    @Test
    fun encryptedValueIsBoundToSourceIdAndDeleteRemovesCredential() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceA = SourceId("security-a-${UUID.randomUUID()}")
        val sourceB = SourceId("security-b-${UUID.randomUUID()}")
        val store = KeystoreCredentialStore(context)
        val prefs = preferences(context)

        try {
            store.put(sourceA, SourceSecret.Xtream("bound-user", "bound-password"))
            val encoded = prefs.getString(storageKey(sourceA), null)
            assertNotNull(encoded)
            assertTrue(prefs.edit().putString(storageKey(sourceB), encoded).commit())

            val wrongSourceFailure = runCatching { store.get(sourceB) }.exceptionOrNull()
            assertTrue(wrongSourceFailure is CredentialStoreException)

            store.delete(sourceA)
            assertNull(store.get(sourceA))
            assertFalse(prefs.contains(storageKey(sourceA)))
        } finally {
            prefs.edit()
                .remove(storageKey(sourceA))
                .remove(storageKey(sourceB))
                .commit()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun storageKey(sourceId: SourceId): String = "source.${sourceId.value}"

    private companion object {
        const val PREFERENCES_NAME = "ownplay_secure_source_credentials"
    }
}
