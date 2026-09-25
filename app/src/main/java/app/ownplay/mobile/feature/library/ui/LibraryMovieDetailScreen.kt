package app.ownplay.mobile.feature.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.ProviderCategoryDisplayPolicy
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.feature.library.data.LibraryArtworkLoader
import app.ownplay.mobile.feature.library.domain.LibraryCatalogSnapshot
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.feature.library.domain.LibraryMovieDetailLoadResult
import app.ownplay.mobile.feature.library.domain.LibraryMovieDetailMetadata
import app.ownplay.mobile.feature.library.domain.LibraryRepository
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.sources.domain.SourceSummary
import kotlinx.coroutines.launch

@Composable
internal fun LibraryMovieDetailScreen(
    source: SourceSummary,
    movieId: String,
    repository: LibraryRepository,
    downloadRepository: DownloadRepository,
    artworkLoader: LibraryArtworkLoader,
    playbackSessionController: PlaybackSessionController,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val movieFlow = remember(repository, source.sourceId, movieId) {
        repository.observeMovie(source.sourceId, movieId)
    }
    val movie by movieFlow.collectAsState(initial = null)
    val downloadsFlow = remember(downloadRepository, source.sourceId) {
        downloadRepository.observeDownloads(source.sourceId)
    }
    val downloads by downloadsFlow.collectAsState(initial = emptyList())
    val catalogFlow = remember(repository, source.sourceId) {
        repository.observeCatalog(source.sourceId)
    }
    val catalog by catalogFlow.collectAsState(
        initial = LibraryCatalogSnapshot(emptyList(), emptyList(), emptyList(), emptyList()),
    )
    val movieDownload = downloads.firstOrNull { item ->
        item.mediaKind == DownloadMediaKind.MOVIE && item.contentId == movieId
    }
    val resumeAvailable = catalog.continueWatching.any { item ->
        item.contentKind == LibraryContentKind.MOVIE && item.contentId == movieId
    }
    var loading by remember(source.sourceId, movieId) { mutableStateOf(true) }
    var detailResult by remember(source.sourceId, movieId) {
        mutableStateOf<LibraryMovieDetailLoadResult?>(null)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository, source.sourceId, movieId) {
        loading = true
        detailResult = repository.loadMovieDetail(source.sourceId, movieId)
        loading = false
    }

    val cachedMovie = movie
    val metadata = (detailResult as? LibraryMovieDetailLoadResult.Loaded)?.metadata

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("Back")
        }

        if (cachedMovie == null) {
            Text(
                text = when (detailResult) {
                    LibraryMovieDetailLoadResult.Unavailable -> "This Movie is no longer available."
                    else -> "Movie details are not available."
                },
                color = OwnPlayColors.TextMuted,
            )
            return@Column
        }

        val resolvedMetadata = metadata ?: LibraryMovieDetailMetadata(
            posterUrl = cachedMovie.posterUrl,
            backdropUrl = cachedMovie.backdropUrl,
            plot = null,
            releaseDate = null,
            year = null,
            runtimeMs = null,
            rating = cachedMovie.rating,
        )
        LibraryDetailHero(
            title = ProviderCategoryDisplayPolicy.label(
                rawName = cachedMovie.title,
                hideRegionPrefix = hideProviderPrefix,
                showFlag = showProviderFlags,
            ),
            artworkUrl = metadata?.posterUrl ?: cachedMovie.posterUrl,
            artworkLoader = artworkLoader,
            metadataLine = LibraryMovieDetailPresentation.metadataLine(resolvedMetadata),
            description = metadata?.plot,
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                playbackSessionController.activateLibraryMedia(
                                    PlaybackTarget.Movie(
                                        sourceId = source.sourceId,
                                        movieId = cachedMovie.movieId,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text(if (resumeAvailable) "Resume" else "Play")
                    }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                repository.setFavorite(
                                    sourceId = source.sourceId,
                                    contentKind = LibraryContentKind.MOVIE,
                                    contentId = cachedMovie.movieId,
                                    favorite = !cachedMovie.favorite,
                                )
                            }
                        },
                    ) {
                        Text(if (cachedMovie.favorite) "Favorited" else "Favorite")
                    }
                }
            },
        )

        metadata?.cast?.let { cast ->
            LibraryCastSection(cast = cast)
        }

        LibraryDownloadActions(
            request = DownloadRequest(
                sourceId = source.sourceId,
                mediaKind = DownloadMediaKind.MOVIE,
                contentId = cachedMovie.movieId,
                title = cachedMovie.title,
            ),
            item = movieDownload,
            repository = downloadRepository,
            playbackSessionController = playbackSessionController,
            offlineResumeAvailable = hasOfflineResumeProgress(
                catalog.continueWatching,
                DownloadMediaKind.MOVIE,
                movieId,
            ),
        )

        when {
            loading -> Text("Loading Movie details…", color = OwnPlayColors.TextMuted)
            detailResult == LibraryMovieDetailLoadResult.UnsupportedSource -> Text(
                "Extended Movie details are not supported for this source.",
                color = OwnPlayColors.TextMuted,
            )
            detailResult == LibraryMovieDetailLoadResult.Failed -> Text(
                "Could not load extended Movie details. Showing cached information.",
                color = OwnPlayColors.TextMuted,
            )
            detailResult == LibraryMovieDetailLoadResult.Unavailable -> Text(
                "This Movie is no longer available.",
                color = OwnPlayColors.TextMuted,
            )
        }
    }
}
