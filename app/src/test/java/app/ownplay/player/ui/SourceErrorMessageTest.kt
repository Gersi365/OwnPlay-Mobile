package app.ownplay.player.ui

import app.ownplay.player.source.SourceError
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceErrorMessageTest {
    @Test
    fun urlValidationErrorsExplainTheActionToTake() {
        assertEquals("Use an HTTP or HTTPS URL.", sourceErrorMessage(SourceError.UnsupportedScheme))
        assertEquals("Include the server host in the URL.", sourceErrorMessage(SourceError.MissingHost))
        assertEquals(
            "Enter username and password in their own fields, not in the URL.",
            sourceErrorMessage(SourceError.EmbeddedCredentialsNotAllowed),
        )
        assertEquals(
            "Remove unsupported parts from the URL.",
            sourceErrorMessage(SourceError.UnexpectedUrlComponent),
        )
    }

    @Test
    fun credentialErrorsDistinguishInputStorageAndAuthentication() {
        assertEquals(
            "Enter a valid username and password.",
            sourceErrorMessage(SourceError.InvalidCredentials),
        )
        assertEquals(
            "Saved credentials could not be read. Re-enter them and try again.",
            sourceErrorMessage(SourceError.CredentialUnavailable),
        )
        assertEquals(
            "Authentication failed. Check your username and password.",
            sourceErrorMessage(SourceError.AuthenticationFailed),
        )
    }

    @Test
    fun connectionAndSourceErrorsOfferARecoveryAction() {
        assertEquals(
            "No network connection. Check your connection and try again.",
            sourceErrorMessage(SourceError.NetworkUnavailable),
        )
        assertEquals(
            "The provider took too long to respond. Try again.",
            sourceErrorMessage(SourceError.Timeout),
        )
        assertEquals(
            "Could not read this source. Check the source and try again.",
            sourceErrorMessage(SourceError.SourceReadFailed),
        )
        assertEquals(
            "OwnPlay could not read this playlist. Check the playlist and try again.",
            sourceErrorMessage(SourceError.MalformedPlaylist),
        )
    }
}
