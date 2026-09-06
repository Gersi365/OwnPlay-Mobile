package app.ownplay.player.source

sealed interface SourceError {
    data object EmptyValue : SourceError
    data object InvalidUrl : SourceError
    data object UnsupportedScheme : SourceError
    data object MissingHost : SourceError
    data object EmbeddedCredentialsNotAllowed : SourceError
    data object UnexpectedUrlComponent : SourceError
    data object UnsupportedLocalUri : SourceError
    data object InvalidCredentials : SourceError
    data object CredentialUnavailable : SourceError
    data object AuthenticationFailed : SourceError
    data object CleartextTransportRequiresOptIn : SourceError
    data object SecureConnectionFailed : SourceError
    data object NetworkUnavailable : SourceError
    data object Timeout : SourceError
    data object SourceReadFailed : SourceError
    data class HttpFailure(val statusCode: Int) : SourceError
    data object MalformedResponse : SourceError
    data object MalformedPlaylist : SourceError
    data object Unknown : SourceError
}
