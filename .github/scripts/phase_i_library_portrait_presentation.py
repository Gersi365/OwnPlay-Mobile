from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


route = Path("app/src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")

replace_once(
    route,
    '''    val showMovieContinueWatching =
        filter == UnifiedLibraryFilter.MOVIES &&
            !offlineOnly &&
            normalizedQuery.isBlank() &&
            vodCatalog.continueWatching.isNotEmpty()
    val showSeriesContinueWatching =
        filter == UnifiedLibraryFilter.SERIES &&
            !offlineOnly &&
            normalizedQuery.isBlank() &&
            seriesCatalog.continueWatching.isNotEmpty()
    val hasItems =
        movieCount + seriesCount > 0 || showMovieContinueWatching || showSeriesContinueWatching
''',
    '''    val continueWatching = remember(vodCatalog.continueWatching, seriesCatalog.continueWatching) {
        unifiedContinueWatching(
            movies = vodCatalog.continueWatching,
            episodes = seriesCatalog.continueWatching,
        )
    }
    val showContinueWatching =
        filter == UnifiedLibraryFilter.MOVIES &&
            !offlineOnly &&
            normalizedQuery.isBlank() &&
            continueWatching.isNotEmpty()
    val hasItems =
        movieCount + seriesCount > 0 || showContinueWatching
''',
)

replace_once(
    route,
    '''    ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "library-search") {
                    IconButton(
                        onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) query = ""
                        },
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchExpanded) {
                                "Close Library search"
                            } else {
                                "Search Library"
                            },
                        )
                    }
                }
                listItems(
                    items = UnifiedLibraryFilter.entries,
                    key = { it.name },
                ) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = {
                            filter = option
                            offlineOnly = option == UnifiedLibraryFilter.OFFLINE
                            query = ""
                            searchExpanded = false
                        },
                        label = {
                            Text(
                                when (option) {
                                    UnifiedLibraryFilter.OFFLINE -> "Offline"
                                    UnifiedLibraryFilter.MOVIES -> "Movies"
                                    UnifiedLibraryFilter.SERIES -> "Series"
                                },
                            )
                        },
                        leadingIcon = if (option == UnifiedLibraryFilter.OFFLINE) {
                            {
                                Icon(
                                    Icons.Filled.DownloadDone,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                if (refreshing && !showInitialMobileLoading) {
                    item(key = "library-refreshing") {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }

        when (filter) {
''',
    '''    ) {
        if (showContinueWatching && sourceId != null) {
            LibraryUnifiedContinueWatchingStrip(
                items = continueWatching,
                onOpenMovie = { movie -> onOpenMovieDetails(sourceId, movie.movieId) },
                onOpenSeries = { episode -> onOpenSeriesDetails(sourceId, episode.seriesId) },
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listItems(
                items = UnifiedLibraryFilter.entries,
                key = { it.name },
            ) { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = {
                        filter = option
                        offlineOnly = option == UnifiedLibraryFilter.OFFLINE
                        query = ""
                        searchExpanded = false
                    },
                    label = {
                        Text(
                            when (option) {
                                UnifiedLibraryFilter.OFFLINE -> "Downloads"
                                UnifiedLibraryFilter.MOVIES -> "Movies"
                                UnifiedLibraryFilter.SERIES -> "Series"
                            },
                        )
                    },
                )
            }
            item(key = "library-search") {
                IconButton(
                    onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) query = ""
                    },
                ) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchExpanded) {
                            "Close Library search"
                        } else {
                            "Search Library"
                        },
                    )
                }
            }
            if (refreshing && !showInitialMobileLoading) {
                item(key = "library-refreshing") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }

        when (filter) {
''',
)

replace_once(
    route,
    '                            UnifiedLibraryFilter.OFFLINE -> "Search Offline"\n',
    '                            UnifiedLibraryFilter.OFFLINE -> "Search Downloads"\n',
)

replace_once(
    route,
    '''        if (showMovieContinueWatching && sourceId != null) {
            LibraryMovieContinueWatchingStrip(
                movies = vodCatalog.continueWatching,
                onOpenMovie = { movie -> onOpenMovieDetails(sourceId, movie.movieId) },
            )
        }

        if (showSeriesContinueWatching && sourceId != null) {
            LibrarySeriesContinueWatchingStrip(
                episodes = seriesCatalog.continueWatching,
                onOpenSeries = { episode -> onOpenSeriesDetails(sourceId, episode.seriesId) },
            )
        }

''',
    '',
)

replace_once(
    route,
    '                text = if (offlineOnly) "Nothing available offline" else "No matching media",\n',
    '                text = if (offlineOnly) "No completed downloads" else "No matching media",\n',
)

replace_once(
    route,
    '                    "Try another category, Library filter or search term."\n',
    '                    "Try another category, section or search term."\n',
)

text = route.read_text()
required = [
    "LibraryUnifiedContinueWatchingStrip(",
    'UnifiedLibraryFilter.OFFLINE -> "Downloads"',
    'UnifiedLibraryFilter.OFFLINE -> "Search Downloads"',
]
for marker in required:
    if marker not in text:
        raise SystemExit(f"Missing Phase I marker: {marker}")
for forbidden in [
    "LibraryMovieContinueWatchingStrip(",
    "LibrarySeriesContinueWatchingStrip(",
    'UnifiedLibraryFilter.OFFLINE -> "Offline"',
    '"Search Offline"',
]:
    if forbidden in text:
        raise SystemExit(f"Legacy Phase I marker remains: {forbidden}")

section_index = text.index("items = UnifiedLibraryFilter.entries")
search_index = text.index('item(key = "library-search")')
continue_index = text.index("LibraryUnifiedContinueWatchingStrip(")
if not (continue_index < section_index < search_index):
    raise SystemExit("Library portrait hierarchy is not Continue Watching -> sections -> Search")
