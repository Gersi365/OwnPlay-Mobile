package app.ownplay.mobile.feature.playback.data

import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.playback.domain.CatchUpPlaybackMediaResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackAlternative
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import app.ownplay.mobile.sources.data.m3u.M3uCatchUpResolver
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
    private val m3uCatchUpResolver: M3uCatchUpResolver,
) : CatchUpPlaybackMediaResolver {
    override suspend fun resolve(target: PlaybackTarget.CatchUp): PreparedPlaybackMedia? {
        val source = sourceDao.get(target.sourceId.value) ?: return null
        if (!source.enabled) return null
        val channel = liveOrganizationDao.getAvailableChannel(
            sourceId = target.sourceId.value,
            channelId = target.channelId,
        ) ?: return null

        return when (runCatching { SourceType.valueOf(source.type) }.getOrNull()) {
            SourceType.XTREAM -> resolveXtream(target, channel, source.baseLocator)
            SourceType.M3U -> resolveM3u(target, channel)
            null -> null
        }
    }

    private suspend fun resolveM3u(
        target: PlaybackTarget.CatchUp,
        channel: LiveChannelEntity,
    ): PreparedPlaybackMedia? {
        val secret = credentialStore.get(target.sourceId) as? SourceSecret.M3uRemote ?: return null
        val entry = m3uCatchUpResolver.resolveEntry(
            secret = secret,
            tvgId = channel.tvgId,
            streamLocator = channel.streamLocator,
        ) ?: return null
        val uri = m3uCatchUpResolver.playbackUrl(
            entry = entry,
            startEpochSeconds = target.startEpochSeconds,
            endEpochSeconds = target.endEpochSeconds,
        ) ?: return null
        return PreparedPlaybackMedia(uri = uri)
    }

    private suspend fun resolveXtream(
        target: PlaybackTarget.CatchUp,
        channel: LiveChannelEntity,
        baseLocator: String,
    ): PreparedPlaybackMedia? {
        val streamId = channel.providerStreamId?.takeIf(String::isNotBlank) ?: return null
        val secret = credentialStore.get(target.sourceId) as? SourceSecret.Xtream ?: return null
        val connection = XtreamConnection(baseLocator, secret.username, secret.password)
        val timezone = runCatching { xtreamClient.accountInfo(connection).timezone }.getOrNull()
        val primaryExtension = XtreamLiveStreamIdentity.extension(
            identity = channel.streamLocator,
            streamId = streamId,
        ) ?: DEFAULT_CATCH_UP_EXTENSION
        val fallbackExtension = XtreamPlaybackFormatPolicy.fallbackExtension(primaryExtension)
        val durationSeconds = target.endEpochSeconds - target.startEpochSeconds

        fun buildUri(extension: String): String? = runCatching {
            XtreamUrlBuilder.catchUpStream(
                baseUrl = baseLocator,
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
