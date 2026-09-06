package app.ownplay.player.ui.vod

import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.onDemandPresentationSession
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.download.OfflineDownloadFeatureRuntime
import app.ownplay.player.download.OfflineDownloadSpec
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.playback.OnDemandContentKind
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.playback.PlaybackMediaKind
import app.ownplay.player.playback.PlaybackPresentationPolicy
import app.ownplay.player.playback.PlaybackRequest
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.vod.VodCatalog
import app.ownplay.player.vod.VodFeatureRuntime
import app.ownplay.player.vod.VodMovie
import app.ownplay.player.vod.VodMovieDetails
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

private const val VOD_CONTROLS_AUTO_HIDE_MILLIS = 3_000L
private const val VOD_EXIT_PROGRESS_SAVE_TIMEOUT_MILLIS = 1_000L

enum class VodSortOrder {
    PROVIDER,
    A_TO_Z,
    NEWEST,
    RATING,
}

@Composable
internal fun VodRoute(
    runtime: OwnPlayAppRuntime,
    sourceId: String?,
    sourceKind: String?,
    requestedMovieId: String? = null,
    onRequestedMovieConsumed: () -> Unit = {},
    returnToLibraryOnDetailBack: Boolean = false,
    onReturnToLibrary: () -> Unit = {},
    onOpenLive: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenSettings: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val featureRuntime = remember(context) { VodFeatureRuntime(context.applicationContext) }
    val downloadRuntime = remember(context) {
        OfflineDownloadFeatureRuntime(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    val onDemandPresentation by runtime.onDemandPresentationSession.state.collectAsState()

    DisposableEffect(featureRuntime) {
        onDispose { featureRuntime.close() }
    }
    DisposableEffect(downloadRuntime) {
        onDispose { downloadRuntime.close() }
    }

    if (sourceId == null) {
        VodUnavailableState(
            title = "No playlist configured",
            body = "Add an Xtream playlist from Settings to load Movies.",
            onOpenSettings = onOpenSettings,
        )
        return
    }
    if (sourceKind != SourceKinds.XTREAM) {
        VodUnavailableState(
            title = "Movies are not available for this source yet",
            body = "Phase 011 currently loads VOD from Xtream sources. Your Live source remains unchanged.",
            onOpenSettings = onOpenSettings,
        )
        return
    }

    val catalog by featureRuntime.observeCatalog(sourceId).collectAsState(initial = VodCatalog())
    val downloads by downloadRuntime.observeAll().collectAsState(initial = emptyList())
    var loading by remember(sourceId) { mutableStateOf(false) }
    var refreshError by remember(sourceId) { mutableStateOf<SourceError?>(null) }
    var query by remember(sourceId) { mutableStateOf("") }
    var selectedCategoryKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var favoritesOnly by remember(sourceId) { mutableStateOf(false) }
    var sortOrder by remember(sourceId) { mutableStateOf(VodSortOrder.PROVIDER) }
    var selectedMovie by remember(sourceId) { mutableStateOf<VodMovie?>(null) }
    var details by remember(sourceId) { mutableStateOf<VodMovieDetails?>(null) }
    var detailsLoading by remember(sourceId) { mutableStateOf(false) }
    var detailsError by remember(sourceId) { mutableStateOf<SourceError?>(null) }
    var restoreDetailFocusAfterPlayback by remember(sourceId) { mutableStateOf(false) }
    var restoreCategoryFocusAfterDetailBack by remember(sourceId) { mutableStateOf(false) }
    val detailsBackOwner = remember(sourceId) { Any() }
    val sessionMoviePlayback = onDemandPresentation.moviePlayback.takeIf {
        onDemandPresentation.kind == OnDemandContentKind.MOVIE &&
            onDemandPresentation.sourceId == sourceId
    }

    fun closeMovieDetails() {
        restoreDetailFocusAfterPlayback = false
        if (returnToLibraryOnDetailBack) {
            runtime.onDemandPresentationSession.clear()
            onReturnToLibrary()
            return
        }
        restoreCategoryFocusAfterDetailBack = true
        selectedMovie = null
        runtime.onDemandPresentationSession.showMovieCatalog(sourceId)
    }

    DisposableEffect(
        selectedMovie?.movieId,
        sessionMoviePlayback?.movieId,
        detailsBackOwner,
        returnToLibraryOnDetailBack,
    ) {
        if (sessionMoviePlayback == null) {
            when {
                selectedMovie != null -> {
                    PlaybackInteractionBridge.registerBackAction(detailsBackOwner, ::closeMovieDetails)
                }
                returnToLibraryOnDetailBack -> {
                    PlaybackInteractionBridge.registerBackAction(detailsBackOwner) {
                        runtime.onDemandPresentationSession.clear()
                        onReturnToLibrary()
                    }
                }
            }
        }
        onDispose {
            PlaybackInteractionBridge.clearBackAction(detailsBackOwner)
        }
    }

    fun downloadFor(movie: VodMovie): OfflineDownload? = downloads.firstOrNull { download ->
        download.sourceId == sourceId &&
            download.mediaKind == DownloadMediaKinds.MOVIE &&
            download.contentId == movie.movieId
    }

    fun enqueueMovieDownload(movie: VodMovie) {
        scope.launch {
            downloadRuntime.enqueue(
                OfflineDownloadSpec(
                    sourceId = sourceId,
                    mediaKind = DownloadMediaKinds.MOVIE,
                    contentId = movie.movieId,
                    providerStreamId = movie.providerStreamId,
                    title = movie.name,
                    posterUrl = movie.posterUrl,
                    containerExtension = movie.containerExtension,
                ),
            )
        }
    }

    fun pauseDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.pause(download.downloadId) }
    }

    fun resumeDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.resume(download.downloadId) }
    }

    fun retryDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.retry(download.downloadId) }
    }

    fun removeDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.remove(download.downloadId) }
    }

    fun setMovieFavorite(movie: VodMovie, favorite: Boolean) {
        scope.launch {
            if (!featureRuntime.setFavorite(sourceId, movie.movieId, favorite)) return@launch
            if (selectedMovie?.movieId == movie.movieId) {
                selectedMovie = selectedMovie?.copy(isFavorite = favorite)
            }
            details = details?.let { current ->
                if (current.movie.movieId != movie.movieId) {
                    current
                } else {
                    current.copy(movie = current.movie.copy(isFavorite = favorite))
                }
            }
        }
    }

    fun clearMovieProgress(movie: VodMovie) {
        scope.launch {
            if (!featureRuntime.clearProgress(sourceId, movie.movieId)) return@launch
            val clearedMovie = movie.copy(
                positionMs = null,
                progressCompleted = false,
                progressUpdatedAtEpochMillis = null,
            )
            if (selectedMovie?.movieId == movie.movieId) {
                selectedMovie = clearedMovie
            }
            details = details?.let { current ->
                if (current.movie.movieId != movie.movieId) {
                    current
                } else {
                    current.copy(
                        movie = current.movie.copy(
                            positionMs = null,
                            progressCompleted = false,
                            progressUpdatedAtEpochMillis = null,
                        ),
                    )
                }
            }
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            refreshError = null
            when (val result = featureRuntime.refresh(sourceId)) {
                is SourceResult.Success -> Unit
                is SourceResult.Failure -> refreshError = result.error
            }
            loading = false
        }
    }

    LaunchedEffect(sourceId) {
        loading = true
        refreshError = null
        when (val result = featureRuntime.refresh(sourceId)) {
            is SourceResult.Success -> Unit
            is SourceResult.Failure -> refreshError = result.error
        }
        loading = false
    }

    LaunchedEffect(sourceId, catalog.categories, requestedMovieId, selectedMovie?.movieId) {
        if (requestedMovieId != null) return@LaunchedEffect
        val selectedIsValid = catalog.categories.any { category ->
            category.providerCategoryKey == selectedCategoryKey
        }
        if (!selectedIsValid) {
            selectedCategoryKey = selectedMovie?.categoryKey
                ?.takeIf { key -> catalog.categories.any { it.providerCategoryKey == key } }
                ?: catalog.categories.firstOrNull()?.providerCategoryKey
        }
    }

    LaunchedEffect(sourceId, requestedMovieId, catalog.movies, catalog.categories) {
        val targetMovieId = requestedMovieId ?: return@LaunchedEffect
        val target = catalog.movies.firstOrNull { movie -> movie.movieId == targetMovieId }
            ?: return@LaunchedEffect
        query = ""
        selectedCategoryKey = target.categoryKey
            ?.takeIf { key -> catalog.categories.any { it.providerCategoryKey == key } }
            ?: catalog.categories.firstOrNull()?.providerCategoryKey
        favoritesOnly = false
        restoreDetailFocusAfterPlayback = false
        restoreCategoryFocusAfterDetailBack = false
        selectedMovie = target
        runtime.onDemandPresentationSession.showMovieDetail(
            sourceId = sourceId,
            movieId = target.movieId,
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
        )
        onRequestedMovieConsumed()
    }

    LaunchedEffect(
        sourceId,
        onDemandPresentation.kind,
        onDemandPresentation.sourceId,
        onDemandPresentation.itemId,
        catalog.movies,
        catalog.categories,
    ) {
        if (
            onDemandPresentation.kind != OnDemandContentKind.MOVIE ||
            onDemandPresentation.sourceId != sourceId
        ) {
            return@LaunchedEffect
        }
        val targetMovieId = onDemandPresentation.itemId ?: return@LaunchedEffect
        val target = catalog.movies.firstOrNull { movie -> movie.movieId == targetMovieId }
            ?: return@LaunchedEffect
        if (selectedMovie?.movieId != target.movieId) {
            selectedMovie = target
            selectedCategoryKey = target.categoryKey
                ?.takeIf { key -> catalog.categories.any { it.providerCategoryKey == key } }
                ?: selectedCategoryKey
        }
    }

    LaunchedEffect(selectedMovie?.movieId) {
        val movie = selectedMovie
        if (movie == null) {
            details = null
            detailsError = null
            detailsLoading = false
            return@LaunchedEffect
        }
        detailsLoading = true
        detailsError = null
        details = when (val result = featureRuntime.details(sourceId, movie.movieId)) {
            is SourceResult.Success -> result.value.copy(
                movie = result.value.movie.copy(
                    isFavorite = movie.isFavorite,
                    positionMs = movie.positionMs,
                    durationMs = movie.durationMs ?: result.value.movie.durationMs,
                    progressCompleted = movie.progressCompleted,
                    progressUpdatedAtEpochMillis = movie.progressUpdatedAtEpochMillis,
                ),
            )
            is SourceResult.Failure -> {
                detailsError = result.error
                null
            }
        }
        detailsLoading = false
    }

    val filteredMovies = remember(
        catalog.movies,
        query,
        selectedCategoryKey,
        favoritesOnly,
        sortOrder,
    ) {
        val normalizedQuery = query.trim().lowercase()
        catalog.movies
            .asSequence()
            .filter { movie -> selectedCategoryKey == null || movie.categoryKey == selectedCategoryKey }
            .filter { movie -> !favoritesOnly || movie.isFavorite }
            .filter { movie -> normalizedQuery.isBlank() || movie.name.lowercase().contains(normalizedQuery) }
            .let { sequence ->
                when (sortOrder) {
                    VodSortOrder.PROVIDER -> sequence
                    VodSortOrder.A_TO_Z -> sequence.sortedBy { it.name.lowercase() }
                    VodSortOrder.NEWEST -> sequence.sortedByDescending { it.addedAtEpochSeconds ?: Long.MIN_VALUE }
                    VodSortOrder.RATING -> sequence.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                }
            }
            .toList()
    }

    val movieToPlay = sessionMoviePlayback
    if (movieToPlay != null) {
        VodPlaybackScreen(
            runtime = runtime,
            featureRuntime = featureRuntime,
            sourceId = sourceId,
            movie = movieToPlay,
            onExit = {
                runtime.onDemandPresentationSession.returnFromMoviePlayback()
                restoreDetailFocusAfterPlayback = true
            },
            onFullscreenStateChanged = onFullscreenStateChanged,
        )
        return
    }

    if (!isLandscape) {
        selectedMovie?.let { movie ->
            MovieDetailsPane(
                movie = movie,
                details = details,
                loading = detailsLoading,
                error = detailsError,
                download = downloadFor(movie),
                focusBackOnEntry = true,
                onDismiss = ::closeMovieDetails,
                onFavoriteChanged = { favorite -> setMovieFavorite(movie, favorite) },
                onDownload = ::enqueueMovieDownload,
                onPauseDownload = ::pauseDownload,
                onResumeDownload = ::resumeDownload,
                onRetryDownload = ::retryDownload,
                onRemoveDownload = ::removeDownload,
                onClearProgress = { clearMovieProgress(movie) },
                onPlay = { target ->
                    restoreDetailFocusAfterPlayback = false
                    runtime.playbackController.start(
                        PlaybackRequest(
                            sourceId = sourceId,
                            channelId = target.movieId,
                            mediaKind = PlaybackMediaKind.MOVIE,
                        ),
                    )
                    runtime.onDemandPresentationSession.showMoviePlayback(
                        sourceId = sourceId,
                        movie = target,
                        returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
            return
        }
    }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MovieCategoryRail(
                catalog = catalog,
                selectedCategoryKey = selectedCategoryKey,
                restoreFocusOnEntry = restoreCategoryFocusAfterDetailBack,
                onFocusRestored = { restoreCategoryFocusAfterDetailBack = false },
                onCategorySelected = { selectedCategoryKey = it },
                modifier = Modifier
                    .widthIn(min = 170.dp, max = 220.dp)
                    .fillMaxHeight(),
            )
            MoviesCatalogContent(
                catalog = catalog,
                movies = filteredMovies,
                loading = loading,
                refreshError = refreshError,
                query = query,
                favoritesOnly = favoritesOnly,
                sortOrder = sortOrder,
                onQueryChanged = { query = it },
                onFavoritesChanged = { favoritesOnly = it },
                onSortChanged = { sortOrder = it },
                onRefresh = ::refresh,
                onMovieSelected = {
                    restoreDetailFocusAfterPlayback = false
                    restoreCategoryFocusAfterDetailBack = false
                    selectedMovie = it
                    runtime.onDemandPresentationSession.showMovieDetail(
                        sourceId = sourceId,
                        movieId = it.movieId,
                        returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
                    )
                },
                showCategoryStrip = false,
                selectedCategoryKey = selectedCategoryKey,
                onCategorySelected = { selectedCategoryKey = it },
                modifier = Modifier.weight(if (selectedMovie == null) 1f else 0.63f),
            )
            selectedMovie?.let { movie ->
                MovieDetailsPane(
                    movie = movie,
                    details = details,
                    loading = detailsLoading,
                    error = detailsError,
                    download = downloadFor(movie),
                    focusBackOnEntry = returnToLibraryOnDetailBack || restoreDetailFocusAfterPlayback,
                    onDismiss = ::closeMovieDetails,
                    onFavoriteChanged = { favorite -> setMovieFavorite(movie, favorite) },
                    onDownload = ::enqueueMovieDownload,
                    onPauseDownload = ::pauseDownload,
                    onResumeDownload = ::resumeDownload,
                    onRetryDownload = ::retryDownload,
                    onRemoveDownload = ::removeDownload,
                    onClearProgress = { clearMovieProgress(movie) },
                    onPlay = { target ->
                        restoreDetailFocusAfterPlayback = false
                        runtime.playbackController.start(
                            PlaybackRequest(
                                sourceId = sourceId,
                                channelId = target.movieId,
                                mediaKind = PlaybackMediaKind.MOVIE,
                            ),
                        )
                        runtime.onDemandPresentationSession.showMoviePlayback(
                            sourceId = sourceId,
                            movie = target,
                            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
                        )
                    },
                    modifier = Modifier
                        .weight(0.37f)
                        .fillMaxHeight(),
                )
            }
        }
    } else {
        MoviesCatalogContent(
            catalog = catalog,
            movies = filteredMovies,
            loading = loading,
            refreshError = refreshError,
            query = query,
            favoritesOnly = favoritesOnly,
            sortOrder = sortOrder,
            onQueryChanged = { query = it },
            onFavoritesChanged = { favoritesOnly = it },
            onSortChanged = { sortOrder = it },
            onRefresh = ::refresh,
            onMovieSelected = {
                restoreDetailFocusAfterPlayback = false
                restoreCategoryFocusAfterDetailBack = false
                selectedMovie = it
                runtime.onDemandPresentationSession.showMovieDetail(
                    sourceId = sourceId,
                    movieId = it.movieId,
                    returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
                )
            },
            showCategoryStrip = true,
            selectedCategoryKey = selectedCategoryKey,
            onCategorySelected = { selectedCategoryKey = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MoviesCatalogContent(
    catalog: VodCatalog,
    movies: List<VodMovie>,
    loading: Boolean,
    refreshError: SourceError?,
    query: String,
    favoritesOnly: Boolean,
    sortOrder: VodSortOrder,
    onQueryChanged: (String) -> Unit,
    onFavoritesChanged: (Boolean) -> Unit,
    onSortChanged: (VodSortOrder) -> Unit,
    onRefresh: () -> Unit,
    onMovieSelected: (VodMovie) -> Unit,
    showCategoryStrip: Boolean,
    selectedCategoryKey: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Movies",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${movies.size} of ${catalog.movies.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = favoritesOnly,
                onClick = { onFavoritesChanged(!favoritesOnly) },
                label = { Text("Favorites") },
                leadingIcon = {
                    Icon(
                        if (favoritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            IconButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh Movies")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search movies") },
            shape = RoundedCornerShape(12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            VodSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = sortOrder == order,
                    onClick = { onSortChanged(order) },
                    label = {
                        Text(
                            when (order) {
                                VodSortOrder.PROVIDER -> "Provider"
                                VodSortOrder.A_TO_Z -> "A–Z"
                                VodSortOrder.NEWEST -> "Newest"
                                VodSortOrder.RATING -> "Rating"
                            },
                        )
                    },
                )
            }
        }

        if (showCategoryStrip) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(catalog.categories, key = { it.categoryId }) { category ->
                    FilterChip(
                        selected = selectedCategoryKey == category.providerCategoryKey,
                        onClick = { onCategorySelected(category.providerCategoryKey) },
                        label = {
                            Text(
                                text = category.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        if (refreshError != null && catalog.movies.isNotEmpty()) {
            Text(
                text = "Refresh failed. Showing saved Movies.",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (catalog.continueWatching.isNotEmpty() && query.isBlank() && !favoritesOnly) {
            Text(
                text = "Continue Watching",
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(catalog.continueWatching, key = { "continue-${it.movieId}" }) { movie ->
                    ContinueWatchingCard(movie = movie, onClick = { onMovieSelected(movie) })
                }
            }
        }

        HorizontalDivider()

        when {
            loading && catalog.movies.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            refreshError != null && catalog.movies.isEmpty() -> VodEmptyCatalog(
                title = "Movies could not be loaded",
                body = sourceErrorLabel(refreshError),
                actionLabel = "Retry",
                onAction = onRefresh,
            )
            movies.isEmpty() -> VodEmptyCatalog(
                title = "No matching Movies",
                body = "Try another category, search term or filter.",
                actionLabel = null,
                onAction = {},
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 128.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(movies, key = { it.movieId }) { movie ->
                    MovieCard(movie = movie, onClick = { onMovieSelected(movie) })
                }
            }
        }
    }
}

@Composable
private fun MovieCategoryRail(
    catalog: VodCatalog,
    selectedCategoryKey: String?,
    restoreFocusOnEntry: Boolean,
    onFocusRestored: () -> Unit,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val categoryFocusRequester = remember { FocusRequester() }
    val focusCategoryKey = selectedCategoryKey
        ?.takeIf { key -> catalog.categories.any { it.providerCategoryKey == key } }
        ?: catalog.categories.firstOrNull()?.providerCategoryKey

    LaunchedEffect(isTelevision, restoreFocusOnEntry, focusCategoryKey) {
        if (!restoreFocusOnEntry) return@LaunchedEffect
        if (isTelevision && focusCategoryKey != null) {
            withFrameNanos { }
            categoryFocusRequester.requestFocus()
        }
        onFocusRestored()
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Movie categories",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(catalog.categories, key = { it.categoryId }) { category ->
                    MovieCategoryRow(
                        title = category.name,
                        selected = selectedCategoryKey == category.providerCategoryKey,
                        onClick = { onCategorySelected(category.providerCategoryKey) },
                        modifier = if (category.providerCategoryKey == focusCategoryKey) {
                            Modifier.focusRequester(categoryFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieCategoryRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .padding(horizontal = 9.dp, vertical = 7.dp)
                .basicMarquee(iterations = Int.MAX_VALUE),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun MovieCard(
    movie: VodMovie,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box {
            RemotePoster(
                url = movie.posterUrl,
                title = movie.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            if (movie.isFavorite) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(5.dp)
                            .size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            movieProgressFraction(movie)?.let { progress ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Text(
            text = movie.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        movie.rating?.let { rating ->
            Text(
                text = "★ %.1f".format(rating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    movie: VodMovie,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RemotePoster(
                url = movie.posterUrl,
                title = movie.name,
                modifier = Modifier
                    .width(54.dp)
                    .aspectRatio(2f / 3f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = movie.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Resume",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VodPlaybackScreen(
    runtime: OwnPlayAppRuntime,
    featureRuntime: VodFeatureRuntime,
    sourceId: String,
    movie: VodMovie,
    onExit: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val playbackState by runtime.playbackController.state.collectAsState()
    val playbackControls = PlaybackPresentationPolicy.controlsFor(playbackState)
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val scope = rememberCoroutineScope()
    val backOwner = remember(movie.movieId) { Any() }
    val backFocusRequester = remember(movie.movieId) { FocusRequester() }
    val controlsFocusRequester = remember(movie.movieId) { FocusRequester() }
    val wakeFocusRequester = remember(movie.movieId) { FocusRequester() }
    var playerView by remember(movie.movieId) { mutableStateOf<PlayerView?>(null) }
    var currentPosition by remember(movie.movieId) { mutableStateOf(movie.positionMs ?: 0L) }
    var duration by remember(movie.movieId) { mutableStateOf(movie.durationMs ?: 0L) }
    var resumeApplied by remember(movie.movieId) { mutableStateOf(false) }
    var controlsVisible by remember(movie.movieId) { mutableStateOf(true) }
    var controlsInteractionToken by remember(movie.movieId) { mutableStateOf(0) }
    var exitRequested by remember(movie.movieId) { mutableStateOf(false) }

    fun revealControls() {
        controlsVisible = true
        controlsInteractionToken += 1
    }

    fun exitPlayback() {
        if (exitRequested) return
        exitRequested = true
        val lastPosition = currentPosition
        val lastDuration = duration.takeIf { it > 0L }
        scope.launch {
            withTimeoutOrNull(VOD_EXIT_PROGRESS_SAVE_TIMEOUT_MILLIS) {
                featureRuntime.saveProgress(sourceId, movie.movieId, lastPosition, lastDuration)
            }
            runtime.playbackController.stopIfCurrent(
                sourceId = sourceId,
                channelId = movie.movieId,
                mediaKind = PlaybackMediaKind.MOVIE,
            )
            onFullscreenStateChanged(false)
            onExit()
        }
    }

    DisposableEffect(movie.movieId, backOwner) {
        onFullscreenStateChanged(true)
        PlaybackInteractionBridge.registerBackAction(backOwner, ::exitPlayback)
        onDispose {
            PlaybackInteractionBridge.clearBackAction(backOwner)
        }
    }

    LaunchedEffect(playbackState, playerView, movie.movieId) {
        val stateRequest = when (val state = playbackState) {
            is PlaybackState.Playing -> state.request
            is PlaybackState.Paused -> state.request
            else -> null
        }
        if (
            !resumeApplied &&
            stateRequest?.mediaKind == PlaybackMediaKind.MOVIE &&
            stateRequest.channelId == movie.movieId
        ) {
            val player = playerView?.player ?: return@LaunchedEffect
            val resumePosition = movie.positionMs
                ?.takeIf { it > 5_000L && !movie.progressCompleted }
            if (resumePosition != null && player.currentPosition < 1_000L) {
                player.seekTo(resumePosition)
                currentPosition = resumePosition
            } else {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
            }
            duration = player.duration.takeIf { it > 0L } ?: duration
            resumeApplied = true
        }
    }

    LaunchedEffect(playerView, movie.movieId) {
        var saveTick = 0
        while (currentCoroutineContext().isActive) {
            delay(1_000L)
            val player = playerView?.player ?: continue
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: duration
            saveTick += 1
            if (saveTick >= 5) {
                saveTick = 0
                featureRuntime.saveProgress(
                    sourceId = sourceId,
                    movieId = movie.movieId,
                    positionMs = currentPosition,
                    durationMs = duration.takeIf { it > 0L },
                )
            }
        }
    }

    LaunchedEffect(playbackState, controlsVisible, controlsInteractionToken, movie.movieId) {
        when (playbackState) {
            is PlaybackState.Playing -> {
                if (controlsVisible) {
                    delay(VOD_CONTROLS_AUTO_HIDE_MILLIS)
                    controlsVisible = false
                }
            }
            is PlaybackState.Loading,
            is PlaybackState.Paused,
            is PlaybackState.Failed,
            -> controlsVisible = true
            PlaybackState.Idle -> Unit
        }
    }

    LaunchedEffect(isTelevision, controlsVisible, playbackState, movie.movieId) {
        if (!isTelevision) return@LaunchedEffect
        when {
            playbackState is PlaybackState.Failed -> backFocusRequester.requestFocus()
            controlsVisible -> controlsFocusRequester.requestFocus()
            else -> wakeFocusRequester.requestFocus()
        }
    }

    val remoteWakeModifier = if (isTelevision && !controlsVisible) {
        Modifier
            .focusRequester(wakeFocusRequester)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.isRemoteNavigationKeyDown()) {
                    revealControls()
                    true
                } else {
                    false
                }
            }
            .focusable()
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (
                        isTelevision &&
                        controlsVisible &&
                        event.nativeKeyEvent.isRemoteNavigationKeyDown()
                    ) {
                        controlsInteractionToken += 1
                    }
                    false
                },
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        runtime.playbackVideoOutput.bind(this)
                        playerView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.useController = false
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    playerView = view
                },
                onRelease = { view ->
                    runtime.playbackVideoOutput.unbind(view)
                    if (playerView === view) playerView = null
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(movie.movieId, controlsVisible) {
                        detectTapGestures {
                            if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                revealControls()
                            }
                        }
                    }
                    .then(remoteWakeModifier),
            )

            if (controlsVisible) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        modifier = Modifier.focusRequester(backFocusRequester),
                        enabled = !exitRequested,
                        onClick = ::exitPlayback,
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        text = movie.name,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.70f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val maxDuration = max(duration, 1L)
                    Slider(
                        value = currentPosition.coerceIn(0L, maxDuration).toFloat(),
                        onValueChange = {
                            currentPosition = it.toLong()
                            revealControls()
                        },
                        onValueChangeFinished = {
                            playerView?.player?.seekTo(currentPosition)
                            revealControls()
                        },
                        valueRange = 0f..maxDuration.toFloat(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val playbackActionEnabled =
                            playbackState is PlaybackState.Playing ||
                                playbackState is PlaybackState.Paused ||
                                (playbackState is PlaybackState.Failed && playbackControls.canRetry)
                        IconButton(
                            modifier = Modifier.focusRequester(controlsFocusRequester),
                            enabled = playbackActionEnabled,
                            onClick = {
                                when (playbackState) {
                                    is PlaybackState.Playing -> runtime.playbackController.pause()
                                    is PlaybackState.Paused -> runtime.playbackController.play()
                                    is PlaybackState.Failed -> if (playbackControls.canRetry) {
                                        runtime.playbackController.retry()
                                    }
                                    else -> Unit
                                }
                                revealControls()
                            },
                        ) {
                            val playing = playbackState is PlaybackState.Playing
                            val failed = playbackState is PlaybackState.Failed
                            Icon(
                                when {
                                    failed -> Icons.Filled.Refresh
                                    playing -> Icons.Filled.Pause
                                    else -> Icons.Filled.PlayArrow
                                },
                                contentDescription = when {
                                    failed -> "Retry"
                                    playing -> "Pause"
                                    else -> "Play"
                                },
                                tint = if (playbackActionEnabled) {
                                    Color.White
                                } else {
                                    Color.White.copy(alpha = 0.38f)
                                },
                            )
                        }
                        Text(
                            text = "${formatDuration(currentPosition)} / ${formatDuration(duration)}",
                            color = Color.White.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        when (playbackState) {
                            is PlaybackState.Loading -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            is PlaybackState.Failed -> Text(
                                text = "Playback failed",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VodUnavailableState(
    title: String,
    body: String,
    onOpenSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun VodEmptyCatalog(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            actionLabel?.let { label -> TextButton(onClick = onAction) { Text(label) } }
        }
    }
}

private fun KeyEvent.isRemoteNavigationKeyDown(): Boolean =
    action == KeyEvent.ACTION_DOWN &&
        keyCode in setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )

private fun movieProgressFraction(movie: VodMovie): Float? {
    val position = movie.positionMs ?: return null
    val duration = movie.durationMs ?: return null
    if (position <= 0L || duration <= 0L || movie.progressCompleted) return null
    return (position.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

private fun downloadProgressLabel(download: OfflineDownload): String {
    val downloaded = humanBytes(download.bytesDownloaded)
    val totalBytes = download.totalBytes?.takeIf { it > 0L }
    val total = totalBytes?.let(::humanBytes)
    val prefix = when (download.state) {
        DownloadStates.PAUSED -> "Paused · "
        DownloadStates.QUEUED -> "Queued · "
        else -> ""
    }
    if (totalBytes == null || total == null) return "$prefix$downloaded"
    val percent = ((download.bytesDownloaded.toDouble() / totalBytes.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)
    return "$prefix$downloaded / $total · $percent%"
}

private fun humanBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1_073_741_824L -> "%.1f GB".format(safe / 1_073_741_824.0)
        safe >= 1_048_576L -> "%.1f MB".format(safe / 1_048_576.0)
        safe >= 1_024L -> "%.1f KB".format(safe / 1_024.0)
        else -> "$safe B"
    }
}

private fun sourceErrorLabel(error: SourceError): String = when (error) {
    SourceError.AuthenticationFailed -> "Provider authentication failed."
    SourceError.CredentialUnavailable -> "Saved credentials are unavailable."
    SourceError.NetworkUnavailable -> "Network unavailable."
    SourceError.Timeout -> "Provider request timed out."
    SourceError.CleartextTransportRequiresOptIn -> "This source requires cleartext opt-in in Settings."
    SourceError.MalformedResponse -> "Provider returned malformed VOD data."
    is SourceError.HttpFailure -> "Provider returned HTTP ${error.statusCode}."
    else -> "Movies could not be refreshed."
}

private fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "00:00"
    val totalSeconds = milliseconds / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
