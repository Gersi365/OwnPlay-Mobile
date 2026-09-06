package app.ownplay.player.playback

import app.ownplay.player.persistence.ChannelAvailability
import app.ownplay.player.persistence.PlaylistSourceDao
import app.ownplay.player.persistence.ProviderCatalogDao
import app.ownplay.player.persistence.SourceKinds

class RoomPlaybackResolutionLookup(
    private val sourceDao: PlaylistSourceDao,
    private val catalogDao: ProviderCatalogDao,
) : PlaybackResolutionLookup {
    override suspend fun sourceById(sourceId: String): PlaybackSourceRecord? =
        sourceDao.getById(sourceId)?.let { source ->
            PlaybackSourceRecord(
                sourceId = source.sourceId,
                sourceKind = when (source.sourceKind) {
                    SourceKinds.XTREAM -> PlaybackResolutionSourceKind.XTREAM
                    SourceKinds.REMOTE_M3U,
                    SourceKinds.LOCAL_M3U,
                    -> PlaybackResolutionSourceKind.M3U
                    else -> PlaybackResolutionSourceKind.OTHER
                },
                locatorRef = source.locatorRef,
                credentialRef = source.credentialRef,
                enabled = source.enabled,
            )
        }

    override suspend fun channelById(channelId: String): PlaybackChannelRecord? =
        catalogDao.channelById(channelId)?.let { channel ->
            PlaybackChannelRecord(
                channelId = channel.channelId,
                sourceId = channel.sourceId,
                streamLocatorRef = channel.streamLocatorRef,
                removed = channel.availability == ChannelAvailability.REMOVED,
            )
        }

    override suspend fun movieById(movieId: String): PlaybackMovieRecord? =
        catalogDao.movieById(movieId)?.let { movie ->
            PlaybackMovieRecord(
                movieId = movie.movieId,
                sourceId = movie.sourceId,
                providerStreamId = movie.providerStreamId.toIntOrNull() ?: return@let null,
                containerExtension = movie.containerExtension,
            )
        }
}
