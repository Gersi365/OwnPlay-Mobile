package app.ownplay.player.source.xtream

import app.ownplay.player.source.PlaylistSource
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.CredentialStore
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException

class XtreamSourceVerifier(
    private val credentialStore: CredentialStore,
    private val client: XtreamClient,
) {
    suspend fun validate(source: PlaylistSource.Xtream): SourceResult<XtreamAccountInfo> {
        val credentials = try {
            credentialStore.get(source.credentialRef)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return SourceResult.Failure(SourceError.CredentialUnavailable)

        return client.validateAccount(
            serverUrl = source.serverUrl,
            credentials = credentials,
        )
    }
}
