package app.ownplay.player.ui

import app.ownplay.player.source.SourceError

internal fun sourceErrorMessage(error: SourceError): String = when (error) {
    SourceError.EmptyValue -> "Complete the required field."
    SourceError.InvalidUrl -> "Enter a valid source URL."
    SourceError.UnsupportedScheme -> "Use an HTTP or HTTPS URL."
    SourceError.MissingHost -> "Include the server host in the URL."
    SourceError.EmbeddedCredentialsNotAllowed ->
        "Enter username and password in their own fields, not in the URL."
    SourceError.UnexpectedUrlComponent -> "Remove unsupported parts from the URL."
    SourceError.UnsupportedLocalUri -> "Choose a supported local playlist file."
    SourceError.InvalidCredentials -> "Enter a valid username and password."
    SourceError.CredentialUnavailable ->
        "Saved credentials could not be read. Re-enter them and try again."
    SourceError.AuthenticationFailed -> "Authentication failed. Check your username and password."
    SourceError.CleartextTransportRequiresOptIn -> "Enable HTTP for this provider, then try again."
    SourceError.SecureConnectionFailed ->
        "Secure connection failed. Check the server address and try again."
    SourceError.NetworkUnavailable -> "No network connection. Check your connection and try again."
    SourceError.Timeout -> "The provider took too long to respond. Try again."
    SourceError.SourceReadFailed -> "Could not read this source. Check the source and try again."
    is SourceError.HttpFailure ->
        "The provider returned HTTP ${error.statusCode}. Try again or check the source details."
    SourceError.MalformedResponse -> "The provider returned data OwnPlay could not read."
    SourceError.MalformedPlaylist ->
        "OwnPlay could not read this playlist. Check the playlist and try again."
    SourceError.Unknown -> "Could not connect to this source. Check the details and try again."
}
