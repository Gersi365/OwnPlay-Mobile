package app.ownplay.mobile.feature.playback.data

import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSource
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSourceResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.sources.domain.SourceType

internal class SourceBackedLivePlaybackSourceResolver(
    private val sourceDao: SourceDao,
    private val liveOrganizationDao: LiveOrganizationDao,
    private val credentialStore: CredentialStore,
) : LivePlaybackSourceResolver {
    override suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource? {
        val source = sourceDao.get(target.sourceId.value) ?: return null
        if (!source.enabled) return null

        val channel = liveOrganizationDao.getAvailableChannel(
            sourceId = target.sourceId.value,
            channelId = target.channelId,
        ) ?: return null

        val sourceType = runCatching { SourceType.valueOf(source.type) }.getOrNull() ?: return null
        val secret = credentialStore.get(target.sourceId) ?: return null

        return when (sourceType) {
            SourceType.M3U -> {
                if (secret !is SourceSecret.M3uRemote) return null
                LivePlaybackSource.Direct(channel.streamLocator)
            }

            SourceType.XTREAM -> {
                val xtream = secret as? SourceSecret.Xtream ?: return null
                val streamId = channel.providerStreamId?.takeIf(String::isNotBlank) ?: return null
                LivePlaybackSource.Xtream(
                    baseUrl = source.baseLocator,
                    username = xtream.username,
                    password = xtream.password,
                    streamId = streamId,
                    opaqueStreamIdentity = channel.streamLocator,
                )
            }
        }
    }
}
