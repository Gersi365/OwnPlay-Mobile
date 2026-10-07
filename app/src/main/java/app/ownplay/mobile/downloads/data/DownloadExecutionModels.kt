package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.downloads.domain.DownloadFilePolicy
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.CatchUpDownloadIdentity
import app.ownplay.mobile.feature.library.data.LibraryPlaybackLocator
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.m3u.M3uCatchUpResolver
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamUrlBuilder
import app.ownplay.mobile.sources.domain.SourceType
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

internal data class ResolvedDownloadMedia(
    internal val uri: String,
    val extension: String,
    val displayName: String,
    val relativeDirectories: List<String>,
    val requiresReportedContentLength: Boolean = false,
) {
    init {
        require(uri.isNotBlank()) { "Resolved download URI must not be blank" }
        require(extension.isNotBlank()) { "Resolved download extension must not be blank" }
        require(displayName.isNotBlank()) { "Resolved download display name must not be blank" }
        require(relativeDirectories.none(String::isBlank)) { "Download directories must not be blank" }
    }

    override fun toString(): String =
        "ResolvedDownloadMedia(uri=<redacted>, extension=$extension, displayName=$displayName, directories=${relativeDirectories.size})"
}

internal interface DownloadMediaResolver {
    suspend fun resolve(item: DownloadItem): ResolvedDownloadMedia?
}

internal interface CatchUpDownloadMediaResolver {
    suspend fun resolve(item: DownloadItem): ResolvedDownloadMedia?
}

internal class SourceBackedDownloadMediaResolver(
    private val libraryDao: LibraryDao,
    private val libraryPlaybackLocator: LibraryPlaybackLocator,
    private val catchUpResolver: CatchUpDownloadMediaResolver? = null,
) : DownloadMediaResolver {
    override suspend fun resolve(item: DownloadItem): ResolvedDownloadMedia? = try {
        when (item.mediaKind) {
            DownloadMediaKind.MOVIE -> resolveMovie(item)
            DownloadMediaKind.EPISODE -> resolveEpisode(item)
            DownloadMediaKind.CATCH_UP -> catchUpResolver?.resolve(item)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveMovie(item: DownloadItem): ResolvedDownloadMedia? {
        val movie = libraryDao.getAvailableMovie(item.sourceId.value, item.contentId) ?: return null
        val extension = DownloadFilePolicy.normalizeFiniteExtension(movie.extension) ?: return null
        val prepared = libraryPlaybackLocator.resolve(
            PlaybackTarget.Movie(sourceId = item.sourceId, movieId = item.contentId),
        ) ?: return null
        return ResolvedDownloadMedia(
            uri = prepared.uri,
            extension = extension,
            displayName = DownloadFilePolicy.fileName(movie.name, extension, item.downloadId.value),
            relativeDirectories = listOf("Movies"),
        )
    }

    private suspend fun resolveEpisode(item: DownloadItem): ResolvedDownloadMedia? {
        val episode = libraryDao.getAvailableEpisode(item.sourceId.value, item.contentId) ?: return null
        val series = libraryDao.getAvailableSeries(item.sourceId.value, episode.seriesId) ?: return null
        val extension = DownloadFilePolicy.normalizeFiniteExtension(episode.extension) ?: return null
        val prepared = libraryPlaybackLocator.resolve(
            PlaybackTarget.Episode(sourceId = item.sourceId, episodeId = item.contentId),
        ) ?: return null
        return ResolvedDownloadMedia(
            uri = prepared.uri,
            extension = extension,
            displayName = DownloadFilePolicy.episodeFileName(
                title = episode.title,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                extension = extension,
                identitySuffix = item.downloadId.value,
            ),
            relativeDirectories = listOf(
                "Series",
                DownloadFilePolicy.safeSegment(series.name, "Series"),
                DownloadFilePolicy.seasonDirectory(episode.seasonNumber),
            ),
        )
    }
}

internal class SourceBackedCatchUpDownloadMediaResolver(
    private val sourceDao: SourceDao,
    private val liveOrganizationDao: LiveOrganizationDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
    private val m3uCatchUpResolver: M3uCatchUpResolver,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : CatchUpDownloadMediaResolver {
    override suspend fun resolve(item: DownloadItem): ResolvedDownloadMedia? {
        if (item.mediaKind != DownloadMediaKind.CATCH_UP) return null
        val identity = CatchUpDownloadIdentity.decode(item.sourceContentIdentity) ?: return null
        if (identity.programId != item.contentId) return null
        val source = sourceDao.get(item.sourceId.value) ?: return null
        if (!source.enabled) return null
        val channel = liveOrganizationDao.getAvailableChannel(item.sourceId.value, identity.channelId)
            ?: return null

        val resolved = when (runCatching { SourceType.valueOf(source.type) }.getOrNull()) {
            SourceType.XTREAM -> resolveXtream(item, identity, source.baseLocator, channel.providerStreamId)
            SourceType.M3U -> resolveM3u(item, identity, channel.tvgId, channel.streamLocator)
            null -> null
        } ?: return null
        val extension = resolved.second
        if (extension !in SUPPORTED_FINITE_EXTENSIONS) return null
        val date = Instant.ofEpochSecond(identity.startEpochSeconds)
            .atZone(ZoneOffset.UTC)
            .format(DATE_FORMATTER)
        return ResolvedDownloadMedia(
            uri = resolved.first,
            extension = extension,
            displayName = DownloadFilePolicy.fileName(item.title, extension, item.downloadId.value),
            relativeDirectories = listOf(
                "Catch-up",
                DownloadFilePolicy.safeSegment(channel.name, "Channel"),
                date,
            ),
            requiresReportedContentLength = true,
        )
    }

    private suspend fun resolveXtream(
        item: DownloadItem,
        identity: CatchUpDownloadIdentity,
        baseLocator: String,
        rawStreamId: String?,
    ): Pair<String, String>? {
        val streamId = rawStreamId?.takeIf(String::isNotBlank) ?: return null
        val secret = credentialStore.get(item.sourceId) as? SourceSecret.Xtream ?: return null
        val connection = XtreamConnection(baseLocator, secret.username, secret.password)
        val stream = xtreamClient.liveStreams(connection).firstOrNull { it.streamId == streamId }
            ?: return null
        if (!stream.catchUpAvailable || !withinArchive(identity.endEpochSeconds, stream.catchUpDurationDays)) {
            return null
        }
        val timezone = runCatching { xtreamClient.accountInfo(connection).timezone }.getOrNull()
        val uri = runCatching {
            XtreamUrlBuilder.catchUpStream(
                baseUrl = baseLocator,
                username = secret.username,
                password = secret.password,
                streamId = streamId,
                extension = "ts",
                startEpochSeconds = identity.startEpochSeconds,
                durationSeconds = identity.endEpochSeconds - identity.startEpochSeconds,
                providerTimezone = timezone,
            )
        }.getOrNull() ?: return null
        return uri to "ts"
    }

    private suspend fun resolveM3u(
        item: DownloadItem,
        identity: CatchUpDownloadIdentity,
        tvgId: String?,
        streamLocator: String,
    ): Pair<String, String>? {
        val secret = credentialStore.get(item.sourceId) as? SourceSecret.M3uRemote ?: return null
        val entry = m3uCatchUpResolver.resolveEntry(secret, tvgId, streamLocator) ?: return null
        if (!m3uCatchUpResolver.supports(entry) || !withinArchive(identity.endEpochSeconds, entry.catchUpDays)) {
            return null
        }
        val uri = m3uCatchUpResolver.playbackUrl(
            entry = entry,
            startEpochSeconds = identity.startEpochSeconds,
            endEpochSeconds = identity.endEpochSeconds,
        ) ?: return null
        val extension = m3uCatchUpResolver.downloadableExtension(
            entry = entry,
            startEpochSeconds = identity.startEpochSeconds,
            endEpochSeconds = identity.endEpochSeconds,
        ) ?: return null
        if (extension !in SUPPORTED_FINITE_EXTENSIONS) return null
        return uri to extension
    }

    private fun withinArchive(endEpochSeconds: Long, days: Int?): Boolean {
        if (days == null || days <= 0) return true
        val seconds = days.toLong().coerceAtMost(Long.MAX_VALUE / SECONDS_PER_DAY) * SECONDS_PER_DAY
        return endEpochSeconds >= nowEpochSeconds() - seconds
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
        val SUPPORTED_FINITE_EXTENSIONS = setOf("ts", "mp4")
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
