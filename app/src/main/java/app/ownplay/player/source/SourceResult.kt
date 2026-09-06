package app.ownplay.player.source

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val error: SourceError) : SourceResult<Nothing>
}
