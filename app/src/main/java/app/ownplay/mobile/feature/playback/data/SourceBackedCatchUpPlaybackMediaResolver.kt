package app.ownplay.mobile.feature.playback.data

import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.playback.domain.CatchUpPlaybackMediaResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackAlternative
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamLiveStreamIdentity
import app.ownplay.mobile.sources.data.xtream.XtreamUrlBuilder
import app.ownplay.mobile.sources.domain.SourceType

internal class SourceBackedCatchUpPlaybackMediaResolver(
    private val sourceDao: SourceDao,
    private val liveOrganizationDao: LiveOrganizationDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
) : CatchUpPlaybackMediaResolver {
    override suspend fun resolve(target: PlaybackTarget.CatchUp): PreparedPlaybackMedia? {
        val source = sourceDao.get(target.sourceId.value) ?: return null
        if (!source.enabled || source.type != SourceType.XTREAM.name) return null

        val channel = liveOrganizationDao.getAvailableChannel(
            sourceId = target.sourceId.value,
            channelId = target.channelId,
        ) ?: return null
        val streamId = channel.providerStreamId?.takeIf(String::isNotBlank) ?: return null
        val secret = credentialStore.get(target.sourceId) as? SourceSecret.Xtream ?: return null
        val connection = XtreamConnection(source.baseLocator, secret.username, secret.password)
        val timezone = runCatching { xtreamClient.accountInfo(connection).timezone }.getOrNull()
        val primaryExtension = XtreamLiveStreamIdentity.extension(
            identity = channel.streamLocator,
            streamId = streamId,
        ) ?: DEFAULT_CATCH_UP_EXTENSION
        val fallbackExtension = XtreamPlaybackFormatPolicy.fallbackExtension(primaryExtension)
        val durationSeconds = target.endEpochSeconds - target.startEpochSeconds

        fun buildUri(extension: String): String? = runCatching {
            XtreamUrlBuilder.catchUpStream(
                baseUrl = source.baseLocator,
                username = secret.username,
                password = secret.password,
                streamId = streamId,
                extension = extension,
                startEpochSeconds = target.startEpochSeconds,
                durationSeconds = durationSeconds,
                providerTimezone = timezone,
            )
        }.getOrNull()

        val uri = buildUri(primaryExtension) ?: return null
        val fallback = fallbackExtension
            ?.let(::buildUri)
            ?.let { fallbackUri ->
                PreparedPlaybackAlternative(
                    uri = fallbackUri,
                    mimeType = XtreamPlaybackFormatPolicy.mimeType(fallbackExtension),
                )
            }

        return PreparedPlaybackMedia(
            uri = uri,
            mimeType = XtreamPlaybackFormatPolicy.mimeType(primaryExtension),
            fallback = fallback,
        )
    }

    private companion object {
        const val DEFAULT_CATCH_UP_EXTENSION = "ts"
    }
}
