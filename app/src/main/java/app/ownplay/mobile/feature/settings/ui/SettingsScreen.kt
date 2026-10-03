package app.ownplay.mobile.feature.settings.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.app.SettingsExitGuard
import app.ownplay.mobile.design.rememberContextLazyListState
import app.ownplay.mobile.sources.domain.SourceConnectionSecurityPolicy
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayMobileTopBar
import app.ownplay.mobile.design.OwnPlayReorderPhase
import app.ownplay.mobile.design.OwnPlayReorderSelectorAction
import app.ownplay.mobile.design.OwnPlayReorderSession
import app.ownplay.mobile.design.ProviderCategoryDisplayPolicy
import app.ownplay.mobile.design.mergeReorderedSubset
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.feature.live.domain.LiveOrganizationRepository
import app.ownplay.mobile.feature.live.domain.ProviderLiveManagementCategory
import app.ownplay.mobile.feature.live.domain.ProviderLiveManagementSnapshot
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.feature.library.domain.LibraryManagementSnapshot
import app.ownplay.mobile.feature.library.domain.LibraryRepository
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.feature.settings.domain.DisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.feature.settings.domain.SourceRefreshScheduleRepository
import app.ownplay.mobile.sources.domain.SourceInput
import app.ownplay.mobile.sources.domain.SourceMutationRejection
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceRepository
import app.ownplay.mobile.sources.domain.SourceSummary
import app.ownplay.mobile.sources.domain.SourceType
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SettingsSection(
    val title: String,
    val summary: String,
) {
    SOURCES("Sources", "Xtream and M3U connections"),
    LIVE_ORGANIZATION("Live Management", "Visibility and order for Live categories and channels"),
    LIBRARY_MANAGEMENT("Library Management", "Movies and Series visibility and order"),
    PLAYBACK("Playback", "Volume and Picture in Picture"),
    DISPLAY("Interface", "Artwork, labels and browsing density"),
    REFRESH("Refresh", "Catalog schedule and network policy"),
    DOWNLOADS("Downloads", "Offline network, storage and notifications"),
    ABOUT("About", "Version and open-source notices"),
}

internal enum class SettingsMessageSeverity {
    INFO,
    SUCCESS,
    ERROR,
}

internal data class SettingsOperationMessage(
    val text: String,
    val severity: SettingsMessageSeverity,
) {
    companion object {
        fun info(text: String) = SettingsOperationMessage(text, SettingsMessageSeverity.INFO)
        fun success(text: String) = SettingsOperationMessage(text, SettingsMessageSeverity.SUCCESS)
        fun error(text: String) = SettingsOperationMessage(text, SettingsMessageSeverity.ERROR)
    }
}

private fun settingsResultMessage(
    succeeded: Boolean,
    success: String,
    failure: String,
): SettingsOperationMessage = if (succeeded) {
    SettingsOperationMessage.success(success)
} else {
    SettingsOperationMessage.error(failure)
}

private val LocalSettingsExitGuard = staticCompositionLocalOf<SettingsExitGuard?> { null }

@Composable
private fun RegisterSettingsExitGuard(onExitRequested: (() -> Unit) -> Unit) {
    val guard = LocalSettingsExitGuard.current
    val currentHandler by rememberUpdatedState(onExitRequested)
    DisposableEffect(guard) {
        val owner = Any()
        guard?.register(owner) { continueNavigation -> currentHandler(continueNavigation) }
        onDispose { guard?.unregister(owner) }
    }
}

private val ReorderSessionSaver = mapSaver(
    save = { session: OwnPlayReorderSession<String> ->
        mapOf("committed" to ArrayList(session.committedIds), "working" to ArrayList(session.workingIds),
            "active" to (session.activeId ?: ""), "phase" to session.phase.name)
    },
    restore = { values ->
        OwnPlayReorderSession(
            committedIds = (values["committed"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            workingIds = (values["working"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            activeId = (values["active"] as? String)?.takeIf(String::isNotEmpty),
            phase = OwnPlayReorderPhase.entries.firstOrNull { it.name == values["phase"] } ?: OwnPlayReorderPhase.FIXED,
        )
    },
)

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    openSources: Boolean = false,
    onOpenSourcesConsumed: () -> Unit = {},
    sourceProvisioningMode: Boolean = false,
    onSourceProvisioningBack: () -> Unit = {},
    exitGuard: SettingsExitGuard? = null,
) {
    CompositionLocalProvider(LocalSettingsExitGuard provides exitGuard) {
        SettingsScreenContent(modifier, openSources, onOpenSourcesConsumed, sourceProvisioningMode, onSourceProvisioningBack)
    }
}

@Composable
private fun SettingsScreenContent(
    modifier: Modifier = Modifier,
    openSources: Boolean = false,
    onOpenSourcesConsumed: () -> Unit = {},
    sourceProvisioningMode: Boolean = false,
    onSourceProvisioningBack: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }

    if (sourceProvisioningMode) {
        SettingsSourcesScreen(
            repository = services.sourceRepository,
            refreshScheduleRepository = services.refreshScheduleRepository,
            displayPreferencesRepository = services.displayPreferencesRepository,
            liveOrganizationRepository = services.liveOrganizationRepository,
            libraryRepository = services.libraryRepository,
            playbackPreferencesRepository = services.playbackPreferencesRepository,
            downloadPreferencesRepository = services.downloadPreferencesRepository,
            section = SettingsSection.SOURCES,
            onBack = onSourceProvisioningBack,
            modifier = modifier,
        )
        return
    }
    var selectedSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSection = selectedSectionName?.let { value ->
        SettingsSection.entries.firstOrNull { it.name == value }
    }
    val exitGuard = LocalSettingsExitGuard.current

    LaunchedEffect(openSources) {
        if (openSources) {
            val open = {
                selectedSectionName = SettingsSection.SOURCES.name
                onOpenSourcesConsumed()
            }
            if (exitGuard == null) open() else exitGuard.requestExit(open)
        }
    }

    BackHandler(enabled = selectedSection != null) {
        selectedSectionName = null
    }

    key(selectedSectionName ?: "settings-home") {
    if (selectedSection == null) {
        SettingsHomeScreen(
            modifier = modifier,
            onOpen = { selectedSectionName = it.name },
        )
    } else {
        SettingsSourcesScreen(
            repository = services.sourceRepository,
            refreshScheduleRepository = services.refreshScheduleRepository,
            displayPreferencesRepository = services.displayPreferencesRepository,
            liveOrganizationRepository = services.liveOrganizationRepository,
            libraryRepository = services.libraryRepository,
            playbackPreferencesRepository = services.playbackPreferencesRepository,
            downloadPreferencesRepository = services.downloadPreferencesRepository,
            section = selectedSection,
            onBack = { selectedSectionName = null },
            modifier = modifier,
        )
    }
    }
}

@Composable
private fun SettingsHomeScreen(
    modifier: Modifier,
    onOpen: (SettingsSection) -> Unit,
) {
    LazyColumn(
        state = rememberContextLazyListState("settings-home"),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Settings",
                    color = OwnPlayColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Sources, playback, interface and offline preferences.",
                    color = OwnPlayColors.TextMuted,
                )
            }
        }
        SettingsSection.entries.forEachIndexed { index, section ->
            item(key = section.name) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable { onOpen(section) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            section.title,
                            color = OwnPlayColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            section.summary,
                            color = OwnPlayColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "›",
                        color = OwnPlayColors.TextMuted,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                if (index < SettingsSection.entries.lastIndex) {
                    HorizontalDivider(color = OwnPlayColors.SurfaceRaised)
                }
            }
        }
    }
}

@Composable
private fun SettingsSourcesScreen(
    repository: SourceRepository,
    refreshScheduleRepository: SourceRefreshScheduleRepository,
    displayPreferencesRepository: DisplayPreferencesRepository,
    liveOrganizationRepository: LiveOrganizationRepository,
    libraryRepository: LibraryRepository,
    playbackPreferencesRepository: PlaybackPreferencesRepository,
    downloadPreferencesRepository: DownloadPreferencesRepository,
    section: SettingsSection,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val sourcesFlow = remember(repository) { repository.observeSources() }
    val activeSourceFlow = remember(repository) { repository.observeActiveSource() }
    val sources by sourcesFlow.collectAsState(initial = emptyList())
    val activeSource by activeSourceFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var busyIds by remember { mutableStateOf(emptySet<String>()) }
    var refreshingIds by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<SettingsOperationMessage?>(null) }
    var addSourcePickerOpen by remember { mutableStateOf(false) }
    var sourceActionsExpanded by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf<SourceType?>(null) }
    var addSubmitting by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var renameSource by remember { mutableStateOf<SourceSummary?>(null) }
    var reconnectSource by remember { mutableStateOf<SourceSummary?>(null) }
    var reconnectError by remember { mutableStateOf<String?>(null) }
    var removeSource by remember { mutableStateOf<SourceSummary?>(null) }

    fun runForSource(source: SourceSummary, block: suspend () -> SettingsOperationMessage) {
        if (source.sourceId.value in busyIds) return
        busyIds = busyIds + source.sourceId.value
        scope.launch {
            try {
                message = block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = SettingsOperationMessage.error("Could not complete the operation for ${source.displayName}.")
            } finally {
                busyIds = busyIds - source.sourceId.value
            }
        }
    }

    val currentAddType = addType
    if (currentAddType != null) {
        AddSourceFormScreen(
            type = currentAddType,
            submitting = addSubmitting,
            errorMessage = addError,
            onBack = {
                if (!addSubmitting) {
                    addError = null
                    addType = null
                }
            },
            onSubmit = { input ->
                if (!addSubmitting) {
                    addSubmitting = true
                    addError = null
                    scope.launch {
                        try {
                            val result = repository.addSource(input)
                            if (result is SourceMutationResult.Success) {
                                message = SettingsOperationMessage.success(
                                    "Source added and catalog imported.",
                                )
                                addType = null
                            } else {
                                addError = mutationMessage(
                                    result = result,
                                    success = "Source added and catalog imported.",
                                ).text
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            addError = "Could not add the source."
                        } finally {
                            addSubmitting = false
                        }
                    }
                }
            },
            modifier = modifier,
        )
        return
    }

    val currentReconnectSource = reconnectSource
    if (currentReconnectSource != null) {
        val reconnectSubmitting = currentReconnectSource.sourceId.value in busyIds
        ReconnectSourceFormScreen(
            source = currentReconnectSource,
            submitting = reconnectSubmitting,
            errorMessage = reconnectError,
            onBack = {
                if (!reconnectSubmitting) {
                    reconnectError = null
                    reconnectSource = null
                }
            },
            onSubmit = { input ->
                if (!reconnectSubmitting) {
                    val sourceId = currentReconnectSource.sourceId.value
                    busyIds = busyIds + sourceId
                    reconnectError = null
                    scope.launch {
                        try {
                            val result = repository.reconnectSource(currentReconnectSource.sourceId, input)
                            val resultMessage = mutationMessage(
                                result = result,
                                success = "${currentReconnectSource.displayName} reconnected and catalog imported.",
                            )
                            if (result is SourceMutationResult.Success) {
                                message = resultMessage
                                reconnectSource = null
                            } else {
                                reconnectError = resultMessage.text
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            reconnectError = "Could not reconnect ${currentReconnectSource.displayName}."
                        } finally {
                            busyIds = busyIds - sourceId
                        }
                    }
                }
            },
            modifier = modifier,
        )
        return
    }

    if (section == SettingsSection.LIVE_ORGANIZATION) {
        LiveManagementScreen(
            source = activeSource,
            repository = liveOrganizationRepository,
            message = message,
            onMessage = { message = it },
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    if (section == SettingsSection.LIBRARY_MANAGEMENT) {
        LibraryManagementScreen(
            source = activeSource,
            repository = libraryRepository,
            message = message,
            onMessage = { message = it },
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        state = rememberContextLazyListState(section.name, activeSource?.sourceId?.value),
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OwnPlayMobileTopBar(
                title = section.title,
                subtitle = section.summary,
                onBack = onBack,
            )
        }

        message?.let { status ->
            item {
                val feedbackSurface = when (status.severity) {
                    SettingsMessageSeverity.ERROR -> OwnPlayColors.Error.copy(alpha = 0.12f)
                    SettingsMessageSeverity.SUCCESS -> OwnPlayColors.Accent.copy(alpha = 0.12f)
                    SettingsMessageSeverity.INFO -> OwnPlayColors.SurfaceRaised
                }
                val feedbackText = when (status.severity) {
                    SettingsMessageSeverity.ERROR -> OwnPlayColors.Error
                    SettingsMessageSeverity.SUCCESS -> OwnPlayColors.Accent
                    SettingsMessageSeverity.INFO -> OwnPlayColors.TextSecondary
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    shape = OwnPlayShapes.Medium,
                    color = feedbackSurface,
                ) {
                    Text(
                        text = status.text,
                        color = feedbackText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (section == SettingsSection.SOURCES) {
            item {
                SettingsSectionCard(
                    title = "Sources",
                    subtitle = "Active: ${activeSource?.displayName ?: "None"}",
                ) {
                    Text(
                        "Manage Xtream and M3U connections without exposing saved credentials.",
                        color = OwnPlayColors.TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = { addSourcePickerOpen = true },
                            shape = OwnPlayShapes.Medium,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) { Text("Add source") }
                        Box {
                            TextButton(
                                enabled = activeSource != null,
                                onClick = { sourceActionsExpanded = true },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Source options" },
                            ) { Text("⋮") }
                            DropdownMenu(
                                expanded = sourceActionsExpanded,
                                onDismissRequest = { sourceActionsExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Clear active source") },
                                    enabled = activeSource != null,
                                    onClick = {
                                        sourceActionsExpanded = false
                                        scope.launch {
                                            message = settingsResultMessage(
                                                succeeded = repository.clearActiveSource(),
                                                success = "No active source selected.",
                                                failure = "Could not clear the active source.",
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (sources.isEmpty()) {
                item {
                    Text(
                        "No sources configured. Add Xtream or M3U to begin.",
                        color = OwnPlayColors.TextMuted,
                    )
                }
            } else {
                items(sources, key = { it.sourceId.value }) { source ->
                    SourceSettingsCard(
                        source = source,
                        isActive = activeSource?.sourceId == source.sourceId,
                        busy = source.sourceId.value in busyIds,
                        refreshing = source.sourceId.value in refreshingIds,
                        onSetActive = {
                            runForSource(source) {
                                settingsResultMessage(
                                    succeeded = repository.setActiveSource(source.sourceId),
                                    success = "${source.displayName} is now active.",
                                    failure = "Could not select ${source.displayName}.",
                                )
                            }
                        },
                        onRefresh = {
                            runForSource(source) {
                                val sourceId = source.sourceId.value
                                refreshingIds = refreshingIds + sourceId
                                try {
                                    when (val result = repository.refreshSource(source.sourceId)) {
                                        SourceRefreshResult.Success ->
                                            SettingsOperationMessage.success("${source.displayName} refreshed.")
                                        is SourceRefreshResult.Failure ->
                                            SettingsOperationMessage.error(
                                                result.safeMessage ?: "${source.displayName} refresh failed.",
                                            )
                                    }
                                } finally {
                                    refreshingIds = refreshingIds - sourceId
                                }
                            }
                        },
                        onReconnect = { reconnectSource = source },
                        onRename = { renameSource = source },
                        onRemove = { removeSource = source },
                    )
                }
            }
        }

        if (section == SettingsSection.PLAYBACK) {
            item {
                PlaybackSettingsSection(
                    repository = playbackPreferencesRepository,
                    onMessage = { message = it },
                )
            }
        }

        if (section == SettingsSection.DISPLAY) {
            item {
                DisplayPreferencesSection(
                    repository = displayPreferencesRepository,
                    onMessage = { message = it },
                )
            }
        }

        if (section == SettingsSection.REFRESH) {
            item {
                RefreshScheduleSection(
                    source = activeSource,
                    repository = refreshScheduleRepository,
                    onMessage = { message = it },
                )
            }
        }

        if (section == SettingsSection.DOWNLOADS) {
            item {
                DownloadSettingsSection(
                    repository = downloadPreferencesRepository,
                    onMessage = { message = it },
                )
            }
        }

        if (section == SettingsSection.ABOUT) {
            item {
                AboutSettingsSection()
            }
        }
    }

    if (addSourcePickerOpen) {
        AddSourceTypeSheet(
            onDismiss = { addSourcePickerOpen = false },
            onSelect = { type ->
                addSourcePickerOpen = false
                addError = null
                addSubmitting = false
                addType = type
            },
        )
    }

    renameSource?.let { source ->
        RenameSourceDialog(
            source = source,
            onDismiss = { renameSource = null },
            onSubmit = { name ->
                runForSource(source) {
                    val result = repository.renameSource(source.sourceId, name)
                    if (result is SourceMutationResult.Success) renameSource = null
                    mutationMessage(result, "Source renamed.")
                }
            },
        )
    }

    removeSource?.let { source ->
        AlertDialog(
            onDismissRequest = { removeSource = null },
            title = { Text("Remove source?") },
            text = {
                Text(
                    "${source.displayName} and its source-scoped app data will be removed. " +
                        "Completed files already published to storage are left in place.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeSource = null
                        runForSource(source) {
                            settingsResultMessage(
                                succeeded = repository.removeSource(source.sourceId),
                                success = "${source.displayName} removed.",
                                failure = "Could not remove ${source.displayName}.",
                            )
                        }
                    },
                ) { Text("Remove", color = OwnPlayColors.Error) }
            },
            dismissButton = { TextButton(onClick = { removeSource = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceSettingsCard(
    source: SourceSummary,
    isActive: Boolean,
    busy: Boolean,
    refreshing: Boolean,
    onSetActive: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember(source.sourceId) { mutableStateOf(false) }
    val authenticationRequired =
        source.refreshFailureCategory == SourceRefreshFailureCategory.AUTHENTICATION
    val nonAuthenticationRefreshFailure =
        source.enabled && source.refreshFailureCategory != null && !authenticationRequired
    val statusLabel = when {
        authenticationRequired -> "Authentication required"
        !source.enabled -> "Credentials required"
        isActive -> "Active"
        else -> "Ready"
    }
    val updatedLabel = source.lastSuccessfulRefreshAtEpochMs?.let {
        "Updated ${DateFormat.getDateTimeInstance().format(Date(it))}"
    } ?: "Never updated"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = source.enabled && !authenticationRequired && !isActive && !busy,
                onClick = onSetActive,
            ),
        shape = OwnPlayShapes.Medium,
        color = if (isActive) OwnPlayColors.SurfaceRaised else OwnPlayColors.Surface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.displayName, color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${source.type.name} · $statusLabel · $updatedLabel",
                        color = if (authenticationRequired) OwnPlayColors.Error else OwnPlayColors.TextSecondary,
                    )
                }
                Column {
                    TextButton(
                        enabled = !busy,
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = "More options for ${source.displayName}"
                        },
                    ) { Text("⋮") }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit name") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        if (source.enabled && !authenticationRequired) {
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = {
                                    menuExpanded = false
                                    onRefresh()
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Remove", color = OwnPlayColors.Error) },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }

            if (refreshing) {
                Text(
                    text = "Refreshing…",
                    color = OwnPlayColors.TextMuted,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }

            if (nonAuthenticationRefreshFailure && !refreshing) {
                Text(
                    text = "Last refresh failed · Showing cached catalog",
                    color = OwnPlayColors.Error,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                FilledTonalButton(
                    enabled = !busy,
                    onClick = onRefresh,
                    shape = OwnPlayShapes.Medium,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Retry") }
            }

            if (authenticationRequired) {
                Text(
                    "Automatic refresh is suspended until credentials are updated.",
                    color = OwnPlayColors.TextMuted,
                )
                FilledTonalButton(
                    enabled = !busy,
                    onClick = onReconnect,
                    shape = OwnPlayShapes.Medium,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Update credentials") }
            } else if (!source.enabled) {
                Text(
                    "Saved credentials are never displayed. Enter credentials to enable this source.",
                    color = OwnPlayColors.TextMuted,
                )
                FilledTonalButton(
                    enabled = !busy,
                    onClick = onReconnect,
                    shape = OwnPlayShapes.Medium,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Enter credentials") }
            }
        }
    }
}

@Composable
private fun RefreshScheduleSection(
    source: SourceSummary?,
    repository: SourceRefreshScheduleRepository,
    onMessage: (SettingsOperationMessage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (source == null) {
        SettingsSectionCard(
            title = "Automatic refresh",
            subtitle = "No active source",
        ) {
            Text(
                "Add or select a source to configure automatic catalog refresh.",
                color = OwnPlayColors.TextMuted,
            )
        }
        return
    }

    val scheduleFlow = remember(repository, source.sourceId) {
        repository.observeSchedule(source.sourceId)
    }
    val wifiOnlyFlow = remember(repository, source.sourceId) {
        repository.observeWifiOnly(source.sourceId)
    }
    val schedule by scheduleFlow.collectAsState(initial = SourceRefreshSchedule.MANUAL)
    val wifiOnly by wifiOnlyFlow.collectAsState(initial = false)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsSectionCard(
            title = "Schedule",
            subtitle = "${source.displayName} · ${schedule.displayName}",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SourceRefreshSchedule.entries.forEach { option ->
                    TextButton(
                        enabled = option != schedule,
                        onClick = {
                            scope.launch {
                                onMessage(
                                    settingsResultMessage(
                                        succeeded = repository.setSchedule(source.sourceId, option),
                                        success = "Refresh schedule set to ${option.displayName}.",
                                        failure = "Could not update refresh schedule.",
                                    ),
                                )
                            }
                        },
                    ) { Text(option.displayName) }
                }
            }
        }

        SettingsToggleRow(
            title = "Wi-Fi only for automatic refresh",
            subtitle = if (wifiOnly) {
                "Scheduled refresh waits for Wi-Fi; manual Refresh can still use mobile data."
            } else {
                "Scheduled refresh can use any connected network."
            },
            checked = wifiOnly,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setWifiOnly(source.sourceId, target),
                            success = if (target) "Wi-Fi-only refresh enabled." else "Wi-Fi-only refresh disabled.",
                            failure = "Could not update Wi-Fi-only refresh.",
                        ),
                    )
                }
            },
        )
    }
}

@Composable
internal fun SettingsSectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = OwnPlayShapes.Medium,
        color = OwnPlayColors.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.takeIf(String::isNotBlank)?.let { value ->
                Text(
                    text = value,
                    color = OwnPlayColors.TextMuted,
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OwnPlayShapes.Medium,
        color = OwnPlayColors.Surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    color = OwnPlayColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    color = OwnPlayColors.TextSecondary,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun DisplayPreferencesSection(
    repository: DisplayPreferencesRepository,
    onMessage: (SettingsOperationMessage) -> Unit,
) {
    val preferences by repository.preferences.collectAsState(initial = DisplayPreferences())
    val scope = rememberCoroutineScope()

    SettingsSectionCard(
        title = "Appearance & labels",
        subtitle = "Live and Library presentation preferences",
    ) {
        SettingsToggleRow(
            title = "Channel logos",
            subtitle = "Show provider channel artwork in Live.",
            checked = preferences.showChannelLogos,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setShowChannelLogos(target),
                            success = if (target) "Channel logos enabled." else "Channel logos hidden.",
                            failure = "Could not update display preference.",
                        ),
                    )
                }
            },
        )
        SettingsToggleRow(
            title = "Hide channel prefixes",
            subtitle = "Use the same recognized country/region prefix rules as other provider labels.",
            checked = preferences.hideChannelPrefix,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setHideChannelPrefix(target),
                            success = if (target) "OwnPlay channel prefixes hidden." else "Full channel names restored.",
                            failure = "Could not update display preference.",
                        ),
                    )
                }
            },
        )
        SettingsToggleRow(
            title = "Country flags",
            subtitle = "Show recognized country flags on Live and Library category labels only; channel and item names stay unchanged.",
            checked = preferences.showCategoryFlags,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setShowCategoryFlags(target),
                            success = if (target) "Country flags enabled for provider categories." else "Country flags hidden.",
                            failure = "Could not update category-flag preference.",
                        ),
                    )
                }
            },
        )
        SettingsToggleRow(
            title = "Hide Live category prefixes",
            subtitle = "Remove only recognized region/country prefixes in Live; provider order and membership stay unchanged.",
            checked = preferences.hideLiveCategoryPrefix,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setHideLiveCategoryPrefix(target),
                            success = if (target) "Live category prefixes hidden." else "Full Live category names restored.",
                            failure = "Could not update Live category-label preference.",
                        ),
                    )
                }
            },
        )
        SettingsToggleRow(
            title = "Hide Library category prefixes",
            subtitle = "Remove only recognized region/country prefixes in Movies and Series; provider categories stay intact.",
            checked = preferences.hideLibraryCategoryPrefix,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setHideLibraryCategoryPrefix(target),
                            success = if (target) "Library category prefixes hidden." else "Full Library category names restored.",
                            failure = "Could not update Library category-label preference.",
                        ),
                    )
                }
            },
        )
        SettingsToggleRow(
            title = "Prefer tvg-name",
            subtitle = "Use provider tvg-name when it is available.",
            checked = preferences.preferTvgName,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setPreferTvgName(target),
                            success = if (target) "tvg-name preferred for channel labels." else "Provider display name preferred.",
                            failure = "Could not update channel-name preference.",
                        ),
                    )
                }
            },
        )
        SettingsToggleRow(
            title = "Compact media rows",
            subtitle = "Reduce row spacing where list layouts are used.",
            checked = preferences.compactMediaRows,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setCompactMediaRows(target),
                            success = if (target) "Compact media rows enabled." else "Compact media rows disabled.",
                            failure = "Could not update display preference.",
                        ),
                    )
                }
            },
        )
    }
}

private enum class LibraryManagementMode(
    val label: String,
    val summary: String,
) {
    VISIBILITY("Visibility", "Choose what appears in Library without changing provider content."),
    ORDER("Order", "Reorder locally while keeping provider-native order as the reset state."),
}

private enum class LibraryManagementTarget(
    val label: String,
    val summary: String,
) {
    CATEGORIES("Categories", "Manage Movies or Series provider categories."),
    TITLES("Titles", "Choose Movies or Series, then a provider category context."),
}

private const val LIBRARY_UNCATEGORIZED_CONTEXT = "__ownplay_library_uncategorized__"
private const val LIBRARY_ALL_TITLES_CONTEXT = "__ownplay_library_all_titles__"

@Composable
private fun LibraryManagementScreen(
    source: SourceSummary?,
    repository: LibraryRepository,
    message: SettingsOperationMessage?,
    onMessage: (SettingsOperationMessage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val sourceKey = source?.sourceId?.value
    var modeName by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    var targetName by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    var kindName by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    var contextKey by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    val mode = modeName?.let { value -> LibraryManagementMode.entries.firstOrNull { it.name == value } }
    val target = targetName?.let { value -> LibraryManagementTarget.entries.firstOrNull { it.name == value } }
    val kind = kindName?.let { value ->
        LibraryContentKind.entries.firstOrNull { it.name == value }
            ?.takeIf { it == LibraryContentKind.MOVIE || it == LibraryContentKind.SERIES }
    }

    fun navigateBackClean() {
        when {
            contextKey != null -> contextKey = null
            kindName != null -> kindName = null
            targetName != null -> targetName = null
            modeName != null -> modeName = null
            else -> onBack()
        }
    }

    if (source == null) {
        BackHandler(onBack = onBack)
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OwnPlayMobileTopBar(
                    title = "Library Management",
                    subtitle = "No active source",
                    onBack = onBack,
                )
            }
            item {
                Text(
                    "Add or select a source before managing Library visibility or order.",
                    color = OwnPlayColors.TextSecondary,
                )
            }
        }
        return
    }

    val managementFlow = remember(repository, source.sourceId) { repository.observeManagement(source.sourceId) }
    val management by managementFlow.collectAsState(initial = LibraryManagementSnapshot())
    val categories = kind?.let { selected -> management.categories.filter { it.contentKind == selected } }.orEmpty()
    val titles = kind?.let { selected -> management.titles.filter { it.contentKind == selected } }.orEmpty()
    val categoryById = remember(categories) { categories.associateBy { it.categoryId } }
    val selectedCategoryId = contextKey
        ?.takeUnless { it == LIBRARY_UNCATEGORIZED_CONTEXT || it == LIBRARY_ALL_TITLES_CONTEXT }
    val allTitles = contextKey == LIBRARY_ALL_TITLES_CONTEXT
    val scopedTitles = when {
        kind == null || target != LibraryManagementTarget.TITLES || contextKey == null -> emptyList()
        allTitles -> titles
        contextKey == LIBRARY_UNCATEGORIZED_CONTEXT -> titles.filter { it.categoryId == null }
        else -> titles.filter { it.categoryId == selectedCategoryId }
    }

    if (mode == LibraryManagementMode.ORDER && target == LibraryManagementTarget.CATEGORIES && kind != null) {
        LiveOrderWorkspace(
            title = "${kind.libraryLabel()} categories",
            subtitle = "Local category order",
            items = categories.map { category ->
                LiveReorderItem(
                    id = category.categoryId,
                    title = category.displayName,
                    subtitle = titles.count { it.categoryId == category.categoryId }.let { count ->
                        if (count == 1) "1 title" else "$count titles"
                    },
                )
            },
            committedIds = categories.map { it.categoryId },
            resetEnabled = categories.any { it.manualOrder != null },
            resetLabel = "Reset category order",
            saveSuccessMessage = "Library category order saved.",
            resetSuccessMessage = "Provider category order restored.",
            onSave = { orderedIds -> repository.setLibraryCategoryOrder(source.sourceId, kind, orderedIds) },
            onReset = { repository.resetLibraryCategoryOrder(source.sourceId, kind) },
            onMessage = onMessage,
            onBack = ::navigateBackClean,
            modifier = modifier,
        )
        return
    }

    if (
        mode == LibraryManagementMode.ORDER &&
        target == LibraryManagementTarget.TITLES &&
        kind != null &&
        contextKey != null
    ) {
        LiveOrderWorkspace(
            title = libraryTitleContextLabel(contextKey, categoryById),
            subtitle = "${kind.libraryLabel()} title order",
            items = scopedTitles.map { title ->
                LiveReorderItem(id = title.contentId, title = title.title)
            },
            committedIds = scopedTitles.map { it.contentId },
            resetEnabled = scopedTitles.any { it.manualOrder != null },
            resetLabel = "Reset title order",
            saveSuccessMessage = "Library title order saved.",
            resetSuccessMessage = "Provider title order restored.",
            onSave = { orderedIds ->
                repository.setLibraryTitleOrder(
                    sourceId = source.sourceId,
                    contentKind = kind,
                    categoryId = selectedCategoryId,
                    orderedContentIds = orderedIds,
                    allTitles = allTitles,
                )
            },
            onReset = {
                repository.resetLibraryTitleOrder(
                    sourceId = source.sourceId,
                    contentKind = kind,
                    categoryId = selectedCategoryId,
                    allTitles = allTitles,
                )
            },
            onMessage = onMessage,
            onBack = ::navigateBackClean,
            modifier = modifier,
        )
        return
    }

    BackHandler { navigateBackClean() }

    val pageTitle = when {
        mode == null -> "Library Management"
        target == null -> mode.label
        kind == null -> target.label
        target == LibraryManagementTarget.TITLES && contextKey == null -> kind.libraryLabel()
        target == LibraryManagementTarget.TITLES -> libraryTitleContextLabel(contextKey, categoryById)
        else -> "${kind.libraryLabel()} categories"
    }
    val pageSubtitle = when {
        mode == null -> source.displayName
        target == null -> mode.summary
        kind == null -> target.summary
        target == LibraryManagementTarget.TITLES && contextKey == null -> "Choose a provider category context"
        mode == LibraryManagementMode.VISIBILITY && target == LibraryManagementTarget.CATEGORIES -> "Category visibility"
        mode == LibraryManagementMode.VISIBILITY && target == LibraryManagementTarget.TITLES -> "Title visibility"
        else -> source.displayName
    }

    LazyColumn(
        state = rememberContextLazyListState(sourceKey, modeName, targetName, kindName, contextKey),
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OwnPlayMobileTopBar(
                title = pageTitle,
                subtitle = pageSubtitle,
                onBack = ::navigateBackClean,
            )
        }
        message?.let { current -> item { LiveManagementMessageBanner(current) } }

        when {
            mode == null -> {
                item {
                    Text(
                        "Manage local Library visibility and order without changing provider identity, favorites or playback data.",
                        color = OwnPlayColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(LibraryManagementMode.entries, key = { it.name }) { option ->
                    LiveManagementChoiceRow(
                        title = option.label,
                        subtitle = option.summary,
                        onClick = {
                            modeName = option.name
                            targetName = null
                            kindName = null
                            contextKey = null
                        },
                    )
                }
            }

            target == null -> {
                items(LibraryManagementTarget.entries, key = { it.name }) { option ->
                    LiveManagementChoiceRow(
                        title = option.label,
                        subtitle = option.summary,
                        onClick = {
                            targetName = option.name
                            kindName = null
                            contextKey = null
                        },
                    )
                }
            }

            kind == null -> {
                items(listOf(LibraryContentKind.MOVIE, LibraryContentKind.SERIES), key = { it.name }) { option ->
                    LiveManagementChoiceRow(
                        title = option.libraryLabel(),
                        subtitle = if (target == LibraryManagementTarget.CATEGORIES) {
                            "Provider categories"
                        } else {
                            "Provider titles"
                        },
                        onClick = {
                            kindName = option.name
                            contextKey = null
                        },
                    )
                }
            }

            target == LibraryManagementTarget.TITLES && contextKey == null -> {
                if (titles.isEmpty()) {
                    item { Text("No provider Library titles are available.", color = OwnPlayColors.TextMuted) }
                } else if (categories.isEmpty()) {
                    item {
                        LiveManagementChoiceRow(
                            title = "All titles",
                            subtitle = "${titles.size} provider titles · no provider categories",
                            onClick = { contextKey = LIBRARY_ALL_TITLES_CONTEXT },
                        )
                    }
                } else {
                    items(categories, key = { it.categoryId }) { category ->
                        val count = titles.count { it.categoryId == category.categoryId }
                        LiveManagementChoiceRow(
                            title = category.displayName,
                            subtitle = if (count == 1) "1 title" else "$count titles",
                            onClick = { contextKey = category.categoryId },
                        )
                    }
                    if (titles.any { it.categoryId == null }) {
                        item {
                            val count = titles.count { it.categoryId == null }
                            LiveManagementChoiceRow(
                                title = "Uncategorized",
                                subtitle = if (count == 1) "1 title" else "$count titles",
                                onClick = { contextKey = LIBRARY_UNCATEGORIZED_CONTEXT },
                            )
                        }
                    }
                }
            }

            mode == LibraryManagementMode.VISIBILITY && target == LibraryManagementTarget.CATEGORIES -> {
                if (categories.isEmpty()) {
                    item { Text("No provider categories are available.", color = OwnPlayColors.TextMuted) }
                } else {
                    items(categories, key = { it.categoryId }) { category ->
                        val count = titles.count { it.categoryId == category.categoryId }
                        LiveVisibilityRow(
                            title = category.displayName,
                            subtitle = if (count == 1) "1 title" else "$count titles",
                            hidden = category.hidden,
                            onToggleHidden = {
                                scope.launch {
                                    onMessage(
                                        settingsResultMessage(
                                            succeeded = repository.setLibraryCategoryHidden(
                                                source.sourceId,
                                                kind,
                                                category.categoryId,
                                                !category.hidden,
                                            ),
                                            success = if (category.hidden) {
                                                "${category.displayName} is visible."
                                            } else {
                                                "${category.displayName} is hidden."
                                            },
                                            failure = "Could not update Library category visibility.",
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            mode == LibraryManagementMode.VISIBILITY && target == LibraryManagementTarget.TITLES -> {
                if (scopedTitles.isEmpty()) {
                    item { Text("No titles are available in this context.", color = OwnPlayColors.TextMuted) }
                } else {
                    items(scopedTitles, key = { it.contentId }) { title ->
                        LiveVisibilityRow(
                            title = title.title,
                            hidden = title.hidden,
                            onToggleHidden = {
                                scope.launch {
                                    onMessage(
                                        settingsResultMessage(
                                            succeeded = repository.setLibraryTitleHidden(
                                                source.sourceId,
                                                kind,
                                                title.contentId,
                                                !title.hidden,
                                            ),
                                            success = if (title.hidden) {
                                                "${title.title} is visible."
                                            } else {
                                                "${title.title} is hidden."
                                            },
                                            failure = "Could not update Library title visibility.",
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LibraryContentKind.libraryLabel(): String = when (this) {
    LibraryContentKind.MOVIE -> "Movies"
    LibraryContentKind.SERIES -> "Series"
    LibraryContentKind.EPISODE -> "Episodes"
}

private fun libraryTitleContextLabel(
    contextKey: String?,
    categoryById: Map<String, app.ownplay.mobile.feature.library.domain.LibraryManagementCategory>,
): String = when (contextKey) {
    LIBRARY_ALL_TITLES_CONTEXT -> "All titles"
    LIBRARY_UNCATEGORIZED_CONTEXT -> "Uncategorized"
    null -> "Titles"
    else -> categoryById[contextKey]?.displayName ?: "Titles"
}


private enum class LiveManagementMode(
    val label: String,
    val summary: String,
) {
    VISIBILITY("Visibility", "Choose what appears in Live without changing provider membership."),
    ORDER("Order", "Reorder locally while keeping provider-native order as the reset state."),
}

private enum class LiveManagementTarget(
    val label: String,
    val summary: String,
) {
    CATEGORIES("Categories by country", "Manage provider categories grouped by country."),
    COUNTRY_ORDER("Country order", "Move one or more country groups while keeping their categories together."),
    GLOBAL_CATEGORIES("Global category order", "Move provider categories across country boundaries."),
    CHANNELS("Channels", "Choose a country and category before managing channels."),
}

private const val LIVE_OTHER_COUNTRY_KEY = "__ownplay_other_country__"

private data class LiveCountryGroup(
    val key: String,
    val label: String,
    val categoryIds: List<String>,
)

private data class LiveReorderItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

private fun liveCountryKey(category: ProviderLiveManagementCategory): String =
    ProviderCategoryDisplayPolicy.present(
        rawName = category.displayName,
        hideRegionPrefix = false,
    ).countryCode ?: LIVE_OTHER_COUNTRY_KEY

private fun liveCountryGroups(
    categories: List<ProviderLiveManagementCategory>,
): List<LiveCountryGroup> {
    val grouped = linkedMapOf<String, MutableList<ProviderLiveManagementCategory>>()
    categories.forEach { category ->
        grouped.getOrPut(liveCountryKey(category)) { mutableListOf() }.add(category)
    }
    return grouped.map { (key, groupCategories) ->
        if (key == LIVE_OTHER_COUNTRY_KEY) {
            LiveCountryGroup(
                key = key,
                label = "Other",
                categoryIds = groupCategories.map { it.categoryId },
            )
        } else {
            val firstPresentation = ProviderCategoryDisplayPolicy.present(
                rawName = groupCategories.first().displayName,
                hideRegionPrefix = false,
            )
            val countryName = Locale("", key)
                .getDisplayCountry(Locale.ENGLISH)
                .trim()
                .takeIf { it.isNotEmpty() && !it.equals(key, ignoreCase = true) }
                ?: key
            LiveCountryGroup(
                key = key,
                label = listOfNotNull(firstPresentation.flagEmoji, countryName).joinToString(" "),
                categoryIds = groupCategories.map { it.categoryId },
            )
        }
    }
}

@Composable
private fun LiveManagementScreen(
    source: SourceSummary?,
    repository: LiveOrganizationRepository,
    message: SettingsOperationMessage?,
    onMessage: (SettingsOperationMessage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val sourceKey = source?.sourceId?.value
    var modeName by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    var targetName by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    var selectedCountryKey by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    var selectedCategoryId by rememberSaveable(sourceKey) { mutableStateOf<String?>(null) }
    val mode = modeName?.let { value -> LiveManagementMode.entries.firstOrNull { it.name == value } }
    val target = targetName?.let { value -> LiveManagementTarget.entries.firstOrNull { it.name == value } }

    fun navigateBackClean() {
        when {
            selectedCategoryId != null -> selectedCategoryId = null
            selectedCountryKey != null -> selectedCountryKey = null
            targetName != null -> targetName = null
            modeName != null -> modeName = null
            else -> onBack()
        }
    }

    if (source == null) {
        BackHandler(onBack = onBack)
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OwnPlayMobileTopBar(
                    title = "Live Management",
                    subtitle = "No active source",
                    onBack = onBack,
                )
            }
            item {
                Text(
                    "Add or select a source before managing Live visibility or order.",
                    color = OwnPlayColors.TextSecondary,
                )
            }
        }
        return
    }

    val managementFlow = remember(repository, source.sourceId) {
        repository.observeProviderManagement(source.sourceId)
    }
    val management by managementFlow.collectAsState(
        initial = ProviderLiveManagementSnapshot(categories = emptyList(), channels = emptyList()),
    )
    val countryGroups = remember(management.categories) { liveCountryGroups(management.categories) }
    val selectedCountry = countryGroups.firstOrNull { it.key == selectedCountryKey }
    val categoriesInCountry = selectedCountry?.let { group ->
        management.categories.filter { it.categoryId in group.categoryIds }
    }.orEmpty()
    val selectedCategory = management.categories.firstOrNull { it.categoryId == selectedCategoryId }
    val channelsInCategory = selectedCategory?.let { category ->
        management.channels.filter { it.categoryId == category.categoryId }
    }.orEmpty()
    val channelCountByCategory = remember(management.channels) {
        management.channels.groupingBy { it.categoryId }.eachCount()
    }
    val countryLabelByCategory = remember(countryGroups) {
        countryGroups.flatMap { group -> group.categoryIds.map { it to group.label } }.toMap()
    }

    if (mode == LiveManagementMode.ORDER && target == LiveManagementTarget.COUNTRY_ORDER) {
        val committedCountryKeys = countryGroups.map(LiveCountryGroup::key)
        LiveBlockOrderWorkspace(
            title = "Country order",
            subtitle = "Selected countries move as one block; categories stay attached.",
            items = countryGroups.map { group ->
                LiveReorderItem(
                    id = group.key,
                    title = group.label,
                    subtitle = "${group.categoryIds.size} categories",
                )
            },
            committedIds = committedCountryKeys,
            resetEnabled = management.categories.any { it.manualOrder != null },
            onSave = { orderedKeys ->
                val groupByKey = countryGroups.associateBy(LiveCountryGroup::key)
                val fullOrder = orderedKeys.flatMap { key -> groupByKey[key]?.categoryIds.orEmpty() }
                repository.setProviderCategoryOrder(source.sourceId, fullOrder)
            },
            onReset = { repository.resetProviderCategoryOrder(source.sourceId) },
            resetLabel = "Reset both category orders",
            saveSuccessMessage = "Country order saved.",
            resetSuccessMessage = "Provider category order restored.",
            onMessage = onMessage,
            onBack = ::navigateBackClean,
            modifier = modifier,
        )
        return
    }

    if (mode == LiveManagementMode.ORDER && target == LiveManagementTarget.GLOBAL_CATEGORIES) {
        val fullCategoryIds = management.categories.map { it.categoryId }
        LiveOrderWorkspace(
            title = "Global category order",
            subtitle = "Local order across all countries · provider identity stays unchanged",
            items = management.categories.map { category ->
                LiveReorderItem(
                    id = category.categoryId,
                    title = category.displayName,
                    subtitle = countryLabelByCategory[category.categoryId],
                )
            },
            committedIds = fullCategoryIds,
            resetEnabled = management.categories.any { it.manualOrder != null },
            resetLabel = "Reset both category orders",
            saveSuccessMessage = "Global category order saved.",
            resetSuccessMessage = "Provider category order restored.",
            onSave = { orderedIds ->
                repository.setProviderCategoryOrder(source.sourceId, orderedIds)
            },
            onReset = { repository.resetProviderCategoryOrder(source.sourceId) },
            onMessage = onMessage,
            onBack = ::navigateBackClean,
            modifier = modifier,
        )
        return
    }

    if (mode == LiveManagementMode.ORDER && target == LiveManagementTarget.CATEGORIES && selectedCountry != null) {
        val scopedIds = categoriesInCountry.map { it.categoryId }
        LiveOrderWorkspace(
            title = "${selectedCountry.label} categories",
            subtitle = "Local category order",
            items = categoriesInCountry.map { category ->
                LiveReorderItem(
                    id = category.categoryId,
                    title = category.displayName,
                    subtitle = (channelCountByCategory[category.categoryId] ?: 0).let { count ->
                        if (count == 1) "1 channel" else "$count channels"
                    },
                )
            },
            committedIds = scopedIds,
            resetEnabled = management.categories.any { it.manualOrder != null },
            resetLabel = "Reset all category order",
            saveSuccessMessage = "Category order saved.",
            resetSuccessMessage = "Provider category order restored.",
            onSave = { reorderedScopedIds ->
                val fullOrder = management.categories.map { it.categoryId }
                repository.setProviderCategoryOrder(
                    source.sourceId,
                    mergeReorderedSubset(
                        fullOrder = fullOrder,
                        scopedIds = scopedIds,
                        reorderedScopedIds = reorderedScopedIds,
                    ),
                )
            },
            onReset = { repository.resetProviderCategoryOrder(source.sourceId) },
            onMessage = onMessage,
            onBack = ::navigateBackClean,
            modifier = modifier,
        )
        return
    }

    if (
        mode == LiveManagementMode.ORDER &&
        target == LiveManagementTarget.CHANNELS &&
        selectedCountry != null &&
        selectedCategory != null
    ) {
        LiveOrderWorkspace(
            title = selectedCategory.displayName,
            subtitle = "Channel order · ${selectedCountry.label}",
            items = channelsInCategory.map { channel ->
                LiveReorderItem(
                    id = channel.channelId,
                    title = channel.localName
                        ?: channel.tvgName?.takeIf(String::isNotBlank)
                        ?: channel.name,
                )
            },
            committedIds = channelsInCategory.map { it.channelId },
            resetEnabled = channelsInCategory.any { it.manualOrder != null },
            resetLabel = "Reset channel order",
            saveSuccessMessage = "Channel order saved.",
            resetSuccessMessage = "Provider channel order restored.",
            onSave = { orderedIds ->
                repository.setProviderChannelOrder(
                    source.sourceId,
                    selectedCategory.categoryId,
                    orderedIds,
                )
            },
            onReset = {
                repository.resetProviderChannelOrder(
                    source.sourceId,
                    selectedCategory.categoryId,
                )
            },
            onMessage = onMessage,
            onBack = ::navigateBackClean,
            modifier = modifier,
        )
        return
    }

    BackHandler { navigateBackClean() }

    val pageTitle = when {
        mode == null -> "Live Management"
        target == null -> mode.label
        selectedCountry == null -> target.label
        target == LiveManagementTarget.CHANNELS && selectedCategory == null -> selectedCountry.label
        selectedCategory != null -> selectedCategory.displayName
        else -> selectedCountry.label
    }
    val pageSubtitle = when {
        mode == null -> source.displayName
        target == null -> mode.summary
        selectedCountry == null -> "Choose a country group"
        target == LiveManagementTarget.CHANNELS && selectedCategory == null -> "Choose a category"
        mode == LiveManagementMode.VISIBILITY && target == LiveManagementTarget.CATEGORIES -> "Category visibility"
        mode == LiveManagementMode.VISIBILITY && target == LiveManagementTarget.CHANNELS -> "Channel visibility"
        else -> source.displayName
    }

    LazyColumn(
        state = rememberContextLazyListState(sourceKey, modeName, targetName, selectedCountryKey, selectedCategoryId),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OwnPlayMobileTopBar(
                title = pageTitle,
                subtitle = pageSubtitle,
                onBack = ::navigateBackClean,
            )
        }

        message?.let { current ->
            item { LiveManagementMessageBanner(current) }
        }

        when {
            mode == null -> {
                item {
                    Text(
                        "Provider identity and membership stay source-controlled. Choose the local personalization you want to manage.",
                        color = OwnPlayColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(LiveManagementMode.entries, key = { it.name }) { option ->
                    LiveManagementChoiceRow(
                        title = option.label,
                        subtitle = option.summary,
                        onClick = {
                            modeName = option.name
                            targetName = null
                            selectedCountryKey = null
                            selectedCategoryId = null
                        },
                    )
                }
            }

            target == null -> {
                val targets = if (mode == LiveManagementMode.ORDER) {
                    listOf(
                        LiveManagementTarget.COUNTRY_ORDER,
                        LiveManagementTarget.GLOBAL_CATEGORIES,
                        LiveManagementTarget.CATEGORIES,
                        LiveManagementTarget.CHANNELS,
                    )
                } else {
                    listOf(LiveManagementTarget.CATEGORIES, LiveManagementTarget.CHANNELS)
                }
                items(targets, key = { it.name }) { option ->
                    LiveManagementChoiceRow(
                        title = option.label,
                        subtitle = option.summary,
                        onClick = {
                            targetName = option.name
                            selectedCountryKey = null
                            selectedCategoryId = null
                        },
                    )
                }
            }

            selectedCountry == null -> {
                if (mode == LiveManagementMode.VISIBILITY && target == LiveManagementTarget.CATEGORIES) {
                    item {
                        FilledTonalButton(
                            enabled = management.categories.any { it.hidden },
                            onClick = {
                                scope.launch {
                                    onMessage(
                                        settingsResultMessage(
                                            succeeded = repository.showAllProviderCategories(source.sourceId),
                                            success = "All provider categories are visible.",
                                            failure = "Could not show all provider categories.",
                                        ),
                                    )
                                }
                            },
                            shape = OwnPlayShapes.Medium,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Show all categories") }
                    }
                }
                if (countryGroups.isEmpty()) {
                    item {
                        Text("No provider Live categories are available.", color = OwnPlayColors.TextMuted)
                    }
                } else {
                    items(countryGroups, key = { it.key }) { group ->
                        val channelCount = management.channels.count { it.categoryId in group.categoryIds }
                        LiveManagementChoiceRow(
                            title = group.label,
                            subtitle = "${group.categoryIds.size} categories · $channelCount channels",
                            onClick = {
                                selectedCountryKey = group.key
                                selectedCategoryId = null
                            },
                        )
                    }
                }
            }

            target == LiveManagementTarget.CHANNELS && selectedCategory == null -> {
                if (categoriesInCountry.isEmpty()) {
                    item { Text("No categories in this country group.", color = OwnPlayColors.TextMuted) }
                } else {
                    items(categoriesInCountry, key = { it.categoryId }) { category ->
                        val count = channelCountByCategory[category.categoryId] ?: 0
                        LiveManagementChoiceRow(
                            title = category.displayName,
                            subtitle = if (count == 1) "1 channel" else "$count channels",
                            onClick = { selectedCategoryId = category.categoryId },
                        )
                    }
                }
            }

            mode == LiveManagementMode.VISIBILITY && target == LiveManagementTarget.CATEGORIES -> {
                items(categoriesInCountry, key = { it.categoryId }) { category ->
                    val count = channelCountByCategory[category.categoryId] ?: 0
                    LiveVisibilityRow(
                        title = category.displayName,
                        subtitle = if (count == 1) "1 channel" else "$count channels",
                        hidden = category.hidden,
                        onToggleHidden = {
                            scope.launch {
                                onMessage(
                                    settingsResultMessage(
                                        succeeded = repository.setProviderCategoryHidden(
                                            source.sourceId,
                                            category.categoryId,
                                            !category.hidden,
                                        ),
                                        success = if (category.hidden) {
                                            "${category.displayName} is visible."
                                        } else {
                                            "${category.displayName} is hidden."
                                        },
                                        failure = "Could not update category visibility.",
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            mode == LiveManagementMode.VISIBILITY && target == LiveManagementTarget.CHANNELS -> {
                if (channelsInCategory.isEmpty()) {
                    item { Text("No channels in this category.", color = OwnPlayColors.TextMuted) }
                } else {
                    items(channelsInCategory, key = { it.channelId }) { channel ->
                        val channelLabel = channel.localName
                            ?: channel.tvgName?.takeIf(String::isNotBlank)
                            ?: channel.name
                        LiveVisibilityRow(
                            title = channelLabel,
                            hidden = channel.hidden,
                            onToggleHidden = {
                                scope.launch {
                                    onMessage(
                                        settingsResultMessage(
                                            succeeded = repository.setProviderChannelHidden(
                                                source.sourceId,
                                                channel.categoryId,
                                                channel.channelId,
                                                !channel.hidden,
                                            ),
                                            success = if (channel.hidden) {
                                                "$channelLabel is visible."
                                            } else {
                                                "$channelLabel is hidden."
                                            },
                                            failure = "Could not update channel visibility.",
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBlockOrderWorkspace(
    title: String,
    subtitle: String,
    items: List<LiveReorderItem>,
    committedIds: List<String>,
    resetEnabled: Boolean,
    onSave: suspend (List<String>) -> Boolean,
    onReset: suspend () -> Boolean,
    resetLabel: String,
    saveSuccessMessage: String,
    resetSuccessMessage: String,
    onMessage: (SettingsOperationMessage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var baseIds by rememberSaveable(title) { mutableStateOf(committedIds) }
    var workingIds by rememberSaveable(title) { mutableStateOf(committedIds) }
    var selectedIds by remember(title) { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember(title) { mutableStateOf(false) }
    var confirmExit by remember(title) { mutableStateOf(false) }
    val dirty = workingIds != baseIds
    val providerChanged = workingIds.size != committedIds.size || workingIds.toSet() != committedIds.toSet()
    var pendingExit by remember(title) { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(committedIds) {
        if (!dirty) { baseIds = committedIds; workingIds = committedIds }
    }
    fun completeExit() { val action = pendingExit ?: onBack; pendingExit = null; action() }
    fun requestExit(action: () -> Unit) {
        if (saving) return
        if (dirty) { pendingExit = action; confirmExit = true } else action()
    }
    RegisterSettingsExitGuard(::requestExit)
    val itemById = remember(items) { items.associateBy(LiveReorderItem::id) }

    fun moveSelection(direction: Int) {
        if (selectedIds.isEmpty() || direction == 0) return
        val firstSelected = workingIds.indexOfFirst { it in selectedIds }
        if (firstSelected < 0) return
        val selectedBlock = workingIds.filter { it in selectedIds }
        val remaining = workingIds.filterNot { it in selectedIds }
        val insertion = workingIds.take(firstSelected).count { it !in selectedIds }
        val target = (insertion + direction).coerceIn(0, remaining.size)
        workingIds = remaining.take(target) + selectedBlock + remaining.drop(target)
    }

    fun save(exitAfterSave: Boolean) {
        if (saving || !dirty || providerChanged) return
        saving = true
        scope.launch {
            val succeeded = onSave(workingIds)
            saving = false
            if (succeeded) {
                onMessage(SettingsOperationMessage.success(saveSuccessMessage))
                baseIds = workingIds
                confirmExit = false
                if (exitAfterSave) completeExit()
            } else {
                onMessage(SettingsOperationMessage.error("Could not save the order."))
            }
        }
    }

    fun requestBack() {
        requestExit(onBack)
    }

    BackHandler { requestBack() }
    LazyColumn(
        state = rememberContextLazyListState(title),
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OwnPlayMobileTopBar(title = title, subtitle = subtitle, onBack = ::requestBack)
        }
        item {
            Text(
                "Select one or more rows, then move the selection up or down. Hidden categories remain in the order.",
                color = OwnPlayColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = selectedIds.isNotEmpty() && !saving, onClick = { moveSelection(-1) }) {
                    Text("Move up")
                }
                TextButton(enabled = selectedIds.isNotEmpty() && !saving, onClick = { moveSelection(1) }) {
                    Text("Move down")
                }
                if (selectedIds.isNotEmpty()) {
                    TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear selection") }
                }
            }
        }
        if (providerChanged) {
            item { Text("The provider list changed. Discard these edits and reopen the order before saving.", color = OwnPlayColors.Error) }
        }
        itemsIndexed(workingIds, key = { _, id -> id }) { index, id ->
            val item = itemById[id] ?: return@itemsIndexed
            val selected = id in selectedIds
            Surface(
                color = if (selected) OwnPlayColors.Accent.copy(alpha = 0.14f) else OwnPlayColors.SurfaceRaised,
                shape = OwnPlayShapes.Medium,
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedIds = if (selected) selectedIds - id else selectedIds + id
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (selected) "✓" else "○", color = OwnPlayColors.Accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        item.subtitle?.let { Text(it, color = OwnPlayColors.TextMuted, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("${index + 1}", color = OwnPlayColors.TextSecondary)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = dirty && !saving && !providerChanged,
                    onClick = { save(exitAfterSave = false) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (saving) "Saving…" else "Save") }
                FilledTonalButton(
                    enabled = resetEnabled && !dirty && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val succeeded = onReset()
                            saving = false
                            onMessage(settingsResultMessage(succeeded, resetSuccessMessage, "Could not reset the order."))
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Reset") }
            }
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { if (!saving) { confirmExit = false; pendingExit = null } },
            title = { Text("Save order changes?") },
            text = { Text("This reorder has pending changes. Save or discard them before leaving.") },
            confirmButton = {
                TextButton(enabled = !saving && !providerChanged, onClick = { save(exitAfterSave = true) }) { Text("Save") }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { baseIds = committedIds; workingIds = committedIds; confirmExit = false; completeExit() }) {
                    Text("Discard")
                }
            },
        )
    }
}

@Composable
private fun LiveManagementMessageBanner(message: SettingsOperationMessage) {
    val feedbackSurface = when (message.severity) {
        SettingsMessageSeverity.ERROR -> OwnPlayColors.Error.copy(alpha = 0.12f)
        SettingsMessageSeverity.SUCCESS -> OwnPlayColors.Accent.copy(alpha = 0.12f)
        SettingsMessageSeverity.INFO -> OwnPlayColors.SurfaceRaised
    }
    val feedbackText = when (message.severity) {
        SettingsMessageSeverity.ERROR -> OwnPlayColors.Error
        SettingsMessageSeverity.SUCCESS -> OwnPlayColors.Accent
        SettingsMessageSeverity.INFO -> OwnPlayColors.TextSecondary
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = OwnPlayShapes.Medium,
        color = feedbackSurface,
    ) {
        Text(
            text = message.text,
            color = feedbackText,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LiveManagementChoiceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = OwnPlayColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = OwnPlayColors.TextMuted)
        }
        HorizontalDivider(color = OwnPlayColors.SurfaceRaised)
    }
}

@Composable
private fun LiveVisibilityRow(
    title: String,
    subtitle: String? = null,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                color = if (hidden) OwnPlayColors.TextMuted else OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.takeIf(String::isNotBlank)?.let { value ->
                Text(value, color = OwnPlayColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (hidden) "Hidden" else "Visible",
                color = OwnPlayColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Switch(
            checked = !hidden,
            onCheckedChange = { onToggleHidden() },
            modifier = Modifier.semantics {
                contentDescription = if (hidden) "Show $title" else "Hide $title"
            },
        )
    }
}

@Composable
private fun LiveOrderWorkspace(
    title: String,
    subtitle: String,
    items: List<LiveReorderItem>,
    committedIds: List<String>,
    resetEnabled: Boolean,
    resetLabel: String,
    saveSuccessMessage: String,
    resetSuccessMessage: String,
    onSave: suspend (List<String>) -> Boolean,
    onReset: suspend () -> Boolean,
    onMessage: (SettingsOperationMessage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var session by rememberSaveable(title, stateSaver = ReorderSessionSaver) { mutableStateOf(OwnPlayReorderSession(committedIds = committedIds)) }
    var saving by remember(title) { mutableStateOf(false) }
    var confirmExit by remember(title) { mutableStateOf(false) }
    var pendingExit by remember(title) { mutableStateOf<(() -> Unit)?>(null) }
    val providerChanged = !session.canCommitTo(committedIds)
    fun completeExit() { val action = pendingExit ?: onBack; pendingExit = null; action() }
    fun requestExit(action: () -> Unit) {
        if (saving) return
        if (session.isDirty) { pendingExit = action; confirmExit = true } else action()
    }
    RegisterSettingsExitGuard(::requestExit)

    LaunchedEffect(committedIds) {
        session = session.syncCommitted(committedIds)
    }
    LaunchedEffect(session.phase) {
        if (session.phase == OwnPlayReorderPhase.SAVED) {
            delay(650)
            session = session.settleSaved()
        }
    }

    fun saveOrder(orderedIds: List<String>, exitAfterSave: Boolean) {
        if (saving || providerChanged) return
        saving = true
        scope.launch {
            val succeeded = onSave(orderedIds)
            saving = false
            if (succeeded) {
                session = session.markSaved()
                confirmExit = false
                onMessage(SettingsOperationMessage.success(saveSuccessMessage))
                if (exitAfterSave) completeExit()
            } else {
                onMessage(SettingsOperationMessage.error("Could not save the order."))
            }
        }
    }

    fun requestBack() {
        requestExit(onBack)
    }

    BackHandler { requestBack() }

    val itemById = remember(items) { items.associateBy { it.id } }
    val orderedItems = session.workingIds.mapNotNull(itemById::get)
    val phaseLabel = when (session.phase) {
        OwnPlayReorderPhase.FIXED -> "Fixed"
        OwnPlayReorderPhase.MOVING -> "Moving"
        OwnPlayReorderPhase.PENDING -> "Pending"
        OwnPlayReorderPhase.SAVED -> "Saved"
    }

    LazyColumn(
        state = rememberContextLazyListState(title),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OwnPlayMobileTopBar(
                title = title,
                subtitle = subtitle,
                onBack = ::requestBack,
            )
        }
        item {
            Surface(
                color = OwnPlayColors.SurfaceRaised,
                shape = OwnPlayShapes.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "$phaseLabel · tap Move, drag the active row, then tap Save.",
                        color = OwnPlayColors.TextSecondary,
                    )
                    if (session.isDirty) {
                        Text(
                            "Order has local unsaved changes.",
                            color = OwnPlayColors.Accent,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (providerChanged) {
            item { Text("The provider list changed. Discard these edits and reopen the order before saving.", color = OwnPlayColors.Error) }
        }
        if (orderedItems.isEmpty()) {
            item { Text("Nothing to reorder here.", color = OwnPlayColors.TextMuted) }
        } else {
            itemsIndexed(
                items = orderedItems,
                key = { _, item -> item.id },
            ) { index, item ->
                LiveReorderRow(
                    modifier = Modifier.animateItem(),
                    item = item,
                    position = index + 1,
                    active = session.activeId == item.id,
                    selectorEnabled = !saving && !providerChanged && (!session.isDirty || session.activeId == item.id),
                    dragEnabled = !saving && !providerChanged && session.activeId == item.id,
                    onSelectorTap = {
                        when (val action = session.onSelectorTap(item.id)) {
                            is OwnPlayReorderSelectorAction.Updated -> session = action.session
                            is OwnPlayReorderSelectorAction.CommitRequested -> {
                                session = action.session
                                saveOrder(action.orderedIds, exitAfterSave = false)
                            }
                        }
                    },
                    onMoveTo = { targetIndex ->
                        session = session.moveActiveTo(targetIndex)
                    },
                    onDragFinished = {
                        session = session.finishDrag()
                    },
                )
            }
        }
        item {
            FilledTonalButton(
                enabled = resetEnabled && !session.isDirty && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val succeeded = onReset()
                        saving = false
                        onMessage(
                            settingsResultMessage(
                                succeeded = succeeded,
                                success = resetSuccessMessage,
                                failure = "Could not reset the order.",
                            ),
                        )
                    }
                },
                shape = OwnPlayShapes.Medium,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(resetLabel) }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { if (!saving) { confirmExit = false; pendingExit = null } },
            title = { Text("Save order changes?") },
            text = { Text("This reorder has pending changes. Save them or discard them before leaving.") },
            confirmButton = {
                TextButton(
                    enabled = !saving && !providerChanged,
                    onClick = { saveOrder(session.workingIds, exitAfterSave = true) },
                ) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        session = OwnPlayReorderSession(committedIds = committedIds)
                        confirmExit = false
                        completeExit()
                    },
                ) { Text("Discard") }
            },
        )
    }
}

@Composable
private fun LiveReorderRow(
    modifier: Modifier = Modifier,
    item: LiveReorderItem,
    position: Int,
    active: Boolean,
    selectorEnabled: Boolean,
    dragEnabled: Boolean,
    onSelectorTap: () -> Unit,
    onMoveTo: (Int) -> Unit,
    onDragFinished: () -> Unit,
) {
    val density = LocalDensity.current
    val dragStepPx = with(density) { 48.dp.toPx() }
    var dragDistancePx by remember(item.id, active) { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        if (dragEnabled) {
            dragDistancePx += delta
            when {
                dragDistancePx <= -dragStepPx -> {
                    onMoveTo(position - 2)
                    dragDistancePx = 0f
                }
                dragDistancePx >= dragStepPx -> {
                    onMoveTo(position)
                    dragDistancePx = 0f
                }
            }
        }
    }

    Surface(
        color = if (active) OwnPlayColors.SurfaceRaised else OwnPlayColors.Surface,
        shape = OwnPlayShapes.Medium,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                enabled = dragEnabled,
                onDragStarted = { dragDistancePx = 0f },
                onDragStopped = {
                    dragDistancePx = 0f
                    onDragFinished()
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = OwnPlayShapes.Small,
                color = OwnPlayColors.Background,
            ) {
                Text(
                    position.toString(),
                    color = OwnPlayColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(item.title, color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                item.subtitle?.takeIf(String::isNotBlank)?.let { value ->
                    Text(value, color = OwnPlayColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (active) {
                    Text(
                        "Drag this row vertically",
                        color = OwnPlayColors.Accent,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            TextButton(
                enabled = selectorEnabled,
                onClick = onSelectorTap,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = if (active) "Save order for ${item.title}" else "Move ${item.title}"
                    },
            ) {
                Text(if (active) "Save" else "Move")
            }
        }
    }
}

@Composable
private fun PlaybackSettingsSection(
    repository: PlaybackPreferencesRepository,
    onMessage: (SettingsOperationMessage) -> Unit,
) {
    val preferences by repository.preferences.collectAsState(initial = PlaybackPreferences())
    val scope = rememberCoroutineScope()
    var sliderVolume by remember(repository) { mutableStateOf(preferences.playerVolume) }
    var volumeCommitInProgress by remember(repository) { mutableStateOf(false) }

    LaunchedEffect(preferences.playerVolume) {
        if (!volumeCommitInProgress) {
            sliderVolume = preferences.playerVolume
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsSectionCard(
            title = "Player volume",
            subtitle = "${(sliderVolume * 100f).toInt()}%",
        ) {
            Text(
                "Default OwnPlay playback volume.",
                color = OwnPlayColors.TextSecondary,
            )
            Slider(
                value = sliderVolume,
                enabled = !volumeCommitInProgress,
                onValueChange = { volume ->
                    sliderVolume = volume.coerceIn(0f, 1f)
                },
                onValueChangeFinished = {
                    if (!volumeCommitInProgress) {
                        val committedVolume = sliderVolume
                        volumeCommitInProgress = true
                        scope.launch {
                            val saved = repository.setPlayerVolume(committedVolume)
                            volumeCommitInProgress = false
                            if (!saved) {
                                sliderVolume = preferences.playerVolume
                                onMessage(SettingsOperationMessage.error("Could not update player volume."))
                            }
                        }
                    }
                },
                valueRange = 0f..1f,
            )
        }

        SettingsToggleRow(
            title = "Automatic Picture in Picture",
            subtitle = if (preferences.automaticPictureInPicture) {
                "Leaving OwnPlay can keep supported playback in PiP."
            } else {
                "Leaving OwnPlay stops playback unless PiP is entered explicitly."
            },
            checked = preferences.automaticPictureInPicture,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setAutomaticPictureInPicture(target),
                            success = if (target) "Auto-enter PiP enabled." else "Auto-enter PiP disabled.",
                            failure = "Could not update playback preference.",
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun DownloadSettingsSection(
    repository: DownloadPreferencesRepository,
    onMessage: (SettingsOperationMessage) -> Unit,
) {
    val preferences by repository.preferences.collectAsState(initial = DownloadPreferences())
    val scope = rememberCoroutineScope()
    var destination by remember(preferences.destinationRelativePath) {
        mutableStateOf(preferences.destinationRelativePath)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsToggleRow(
            title = "Wi-Fi only",
            subtitle = if (preferences.wifiOnly) {
                "New download work waits for Wi-Fi."
            } else {
                "Downloads may use any connected network."
            },
            checked = preferences.wifiOnly,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setUnmeteredNetworkOnly(target),
                            success = if (target) "Wi-Fi-only downloads enabled." else "Wi-Fi-only downloads disabled.",
                            failure = "Could not update download network preference.",
                        ),
                    )
                }
            },
        )

        SettingsSectionCard(
            title = "Download destination",
            subtitle = preferences.destinationRelativePath,
        ) {
            Text(
                "Changing the destination affects new downloads only.",
                color = OwnPlayColors.TextMuted,
            )
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Folder under Download/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        onMessage(
                            settingsResultMessage(
                                succeeded = repository.setDestinationRelativePath(destination),
                                success = "Download destination updated for new downloads.",
                                failure = "Use a valid path under Download/, for example Download/OwnPlay Downloads/.",
                            ),
                        )
                    }
                },
                shape = OwnPlayShapes.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Apply destination")
            }
        }

        SettingsToggleRow(
            title = "Result notifications",
            subtitle = if (preferences.notificationsEnabled) {
                "Show completion and failure notifications. Download controls remain available."
            } else {
                "Progress and Pause/Resume controls remain available for active or paused downloads."
            },
            checked = preferences.notificationsEnabled,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        settingsResultMessage(
                            succeeded = repository.setNotificationsEnabled(target),
                            success = if (target) {
                                "Download result notifications enabled."
                            } else {
                                "Download result notifications disabled."
                            },
                            failure = "Could not update download notification preference.",
                        ),
                    )
                }
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AboutSettingsSection() {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "Unknown"
    }
    val buildType = remember(context) {
        if (
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) {
            "debug"
        } else {
            "release"
        }
    }
    var notice by remember { mutableStateOf<AboutNotice?>(null) }

    SettingsSectionCard(
        title = "OwnPlay",
        subtitle = "Version $versionName · $buildType",
    ) {
        Text(
            "Open-source and bundled-component notices.",
            color = OwnPlayColors.TextSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = {
                    notice = AboutNotice(
                        title = "Third-party notices",
                        body = THIRD_PARTY_NOTICES,
                    )
                },
            ) { Text("Third-party notices") }
            TextButton(
                onClick = {
                    notice = AboutNotice(
                        title = "FFmpeg notice",
                        body = readAboutAsset(context, "licenses/ffmpeg/LICENSE.md"),
                    )
                },
            ) { Text("FFmpeg") }
            TextButton(
                onClick = {
                    notice = AboutNotice(
                        title = "GNU LGPL v2.1",
                        body = readAboutAsset(context, "licenses/ffmpeg/COPYING.LGPLv2.1"),
                    )
                },
            ) { Text("LGPL v2.1") }
            TextButton(
                onClick = {
                    notice = AboutNotice(
                        title = "GNU LGPL v3",
                        body = readAboutAsset(context, "licenses/ffmpeg/COPYING.LGPLv3"),
                    )
                },
            ) { Text("LGPL v3") }
        }
    }

    notice?.let { selected ->
        ModalBottomSheet(
            onDismissRequest = { notice = null },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        selected.title,
                        color = OwnPlayColors.TextPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item {
                    Text(selected.body, color = OwnPlayColors.TextSecondary)
                }
                item {
                    TextButton(
                        onClick = { notice = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

private data class AboutNotice(
    val title: String,
    val body: String,
)

private fun readAboutAsset(
    context: Context,
    path: String,
): String = runCatching {
    context.assets.open(path).bufferedReader().use { it.readText() }
}.getOrElse {
    "This bundled license notice could not be read."
}

private const val THIRD_PARTY_NOTICES = """AndroidX / Media3 / Room / WorkManager — Apache License 2.0
Kotlin / kotlinx.coroutines — Apache License 2.0
OkHttp — Apache License 2.0
FFmpeg 6.0 audio decoder — separately licensed; this packaged build does not enable GPL/nonfree options. Applicable FFmpeg/LGPL license texts are available below."""

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceTypeSheet(
    onDismiss: () -> Unit,
    onSelect: (SourceType) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Add source",
                color = OwnPlayColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Choose the connection type.",
                color = OwnPlayColors.TextSecondary,
            )
            SourceTypeChoiceRow(
                title = "Xtream",
                summary = "Server URL, username and password",
                onClick = { onSelect(SourceType.XTREAM) },
            )
            SourceTypeChoiceRow(
                title = "M3U",
                summary = "Playlist URL",
                onClick = { onSelect(SourceType.M3U) },
            )
        }
    }
}

@Composable
private fun SourceTypeChoiceRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick),
        shape = OwnPlayShapes.Medium,
        color = OwnPlayColors.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(summary, color = OwnPlayColors.TextSecondary)
        }
    }
}

@Composable
private fun AddSourceFormScreen(
    type: SourceType,
    submitting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSubmit: (SourceInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (type) {
        SourceType.XTREAM -> XtreamSourceFormScreen(
            submitting = submitting,
            errorMessage = errorMessage,
            onBack = onBack,
            onSubmit = onSubmit,
            modifier = modifier,
        )
        SourceType.M3U -> M3uSourceFormScreen(
            submitting = submitting,
            errorMessage = errorMessage,
            onBack = onBack,
            onSubmit = onSubmit,
            modifier = modifier,
        )
    }
}

@Composable
private fun XtreamSourceFormScreen(
    submitting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSubmit: (SourceInput.Xtream) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    SourceFormScaffold(
        title = "Add Xtream source",
        submitting = submitting,
        errorMessage = errorMessage,
        confirmEnabled = name.isNotBlank() &&
            server.isNotBlank() &&
            username.isNotBlank() &&
            password.isNotBlank(),
        hasUnsavedInput = name.isNotEmpty() || server.isNotEmpty() || username.isNotEmpty() || password.isNotEmpty(),
        onBack = onBack,
        onConfirm = { onSubmit(SourceInput.Xtream(name, server, username, password)) },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            enabled = !submitting,
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            enabled = !submitting,
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SourceTransportWarning(server)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            enabled = !submitting,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            enabled = !submitting,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun M3uSourceFormScreen(
    submitting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSubmit: (SourceInput.M3u) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var playlist by remember { mutableStateOf("") }

    SourceFormScaffold(
        title = "Add M3U source",
        submitting = submitting,
        errorMessage = errorMessage,
        confirmEnabled = name.isNotBlank() && playlist.isNotBlank(),
        hasUnsavedInput = name.isNotEmpty() || playlist.isNotEmpty(),
        onBack = onBack,
        onConfirm = {
            onSubmit(
                SourceInput.M3u(
                    displayName = name,
                    playlistUrl = playlist,
                    epgUrl = null,
                ),
            )
        },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            enabled = !submitting,
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = playlist,
            onValueChange = { playlist = it },
            enabled = !submitting,
            label = { Text("Playlist URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SourceTransportWarning(playlist)
        Text(
            "XMLTV / EPG is not used by this build.",
            color = OwnPlayColors.TextSecondary,
        )
    }
}

@Composable
private fun ReconnectSourceFormScreen(
    source: SourceSummary,
    submitting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSubmit: (SourceReconnectInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (source.type) {
        SourceType.XTREAM -> {
            var server by remember(source.sourceId) { mutableStateOf(source.connectionLabel) }
            var username by remember(source.sourceId) { mutableStateOf("") }
            var password by remember(source.sourceId) { mutableStateOf("") }
            SourceFormScaffold(
                title = "Reconnect ${source.displayName}",
                submitting = submitting,
                errorMessage = errorMessage,
                confirmLabel = "Reconnect",
                confirmEnabled = server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                hasUnsavedInput = server != source.connectionLabel || username.isNotEmpty() || password.isNotEmpty(),
                onBack = onBack,
                onConfirm = { onSubmit(SourceReconnectInput.Xtream(server, username, password)) },
                modifier = modifier,
            ) {
                Text(
                    "Saved credentials are never redisplayed. Enter them again to reconnect this source.",
                    color = OwnPlayColors.TextSecondary,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    enabled = !submitting,
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SourceTransportWarning(server)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    enabled = !submitting,
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !submitting,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SourceType.M3U -> {
            var playlist by remember(source.sourceId) { mutableStateOf(source.connectionLabel) }
            SourceFormScaffold(
                title = "Reconnect ${source.displayName}",
                submitting = submitting,
                errorMessage = errorMessage,
                confirmLabel = "Reconnect",
                confirmEnabled = playlist.isNotBlank(),
                hasUnsavedInput = playlist != source.connectionLabel,
                onBack = onBack,
                onConfirm = {
                    onSubmit(SourceReconnectInput.M3u(playlistUrl = playlist, epgUrl = null))
                },
                modifier = modifier,
            ) {
                Text(
                    "Private playlist parameters are never displayed. Enter the playlist URL again if required.",
                    color = OwnPlayColors.TextSecondary,
                )
                OutlinedTextField(
                    value = playlist,
                    onValueChange = { playlist = it },
                    enabled = !submitting,
                    label = { Text("Playlist URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SourceTransportWarning(playlist)
                Text(
                    "XMLTV / EPG is not used by this build.",
                    color = OwnPlayColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SourceTransportWarning(url: String) {
    SourceConnectionSecurityPolicy.transportWarning(url)?.let { warning ->
        Text(warning, color = OwnPlayColors.Error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SourceFormScaffold(
    title: String,
    submitting: Boolean,
    errorMessage: String?,
    confirmLabel: String = "Save",
    confirmEnabled: Boolean = true,
    hasUnsavedInput: Boolean = false,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun requestExit(action: () -> Unit) {
        if (submitting) return
        if (hasUnsavedInput) { pendingExit = action; confirmDiscard = true } else action()
    }
    RegisterSettingsExitGuard(::requestExit)
    BackHandler { requestExit(onBack) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        OwnPlayMobileTopBar(
            title = title,
            onBack = if (submitting) null else ({ requestExit(onBack) }),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            if (submitting) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Validating source and importing catalog…",
                        color = OwnPlayColors.TextSecondary,
                    )
                }
            }
            errorMessage?.let { error ->
                Text(
                    text = error,
                    color = OwnPlayColors.Error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        FilledTonalButton(
            enabled = confirmEnabled && !submitting,
            onClick = onConfirm,
            shape = OwnPlayShapes.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text(if (submitting) "Saving…" else confirmLabel)
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false; pendingExit = null },
            title = { Text("Discard source draft?") },
            text = { Text("Your entered changes have not been saved. Continue editing or discard this draft.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; pendingExit = null }) { Text("Continue editing") } },
            dismissButton = { TextButton(onClick = {
                val action = pendingExit ?: onBack
                pendingExit = null
                confirmDiscard = false
                action()
            }) { Text("Discard") } },
        )
    }
}

@Composable
private fun RenameSourceDialog(
    source: SourceSummary,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var name by remember(source.sourceId) { mutableStateOf(source.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename source") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                singleLine = true,
            )
        },
        confirmButton = {
            FilledTonalButton(
                enabled = name.isNotBlank(),
                onClick = { onSubmit(name) },
                shape = OwnPlayShapes.Medium,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Cancel") }
        },
    )
}

private fun mutationMessage(
    result: SourceMutationResult,
    success: String,
): SettingsOperationMessage = when (result) {
    is SourceMutationResult.Success -> SettingsOperationMessage.success(success)
    is SourceMutationResult.Rejected -> SettingsOperationMessage.error(
        when (result.reason) {
            SourceMutationRejection.INVALID_NAME -> "Enter a valid display name."
            SourceMutationRejection.INVALID_CONNECTION -> "Enter a valid source connection."
            SourceMutationRejection.INVALID_CREDENTIALS -> "Enter valid source credentials."
            SourceMutationRejection.DUPLICATE_SOURCE -> "That source connection is already configured."
            SourceMutationRejection.STORAGE_FAILURE -> "The source change could not be saved."
        },
    )
}
