package app.ownplay.mobile.feature.settings.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.feature.live.domain.LiveOrganizationRepository
import app.ownplay.mobile.feature.live.domain.ProviderLiveManagementSnapshot
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestoreRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class SettingsSection(
    val title: String,
    val summary: String,
) {
    SOURCES("Sources", "Providers, credentials and active source"),
    LIVE_ORGANIZATION("Live provider", "Provider categories, channels and visibility"),
    PLAYBACK("Playback", "Player volume and Picture in Picture"),
    DISPLAY("Interface", "Artwork, labels and browsing density"),
    REFRESH("Refresh", "Catalog refresh schedule and network policy"),
    DOWNLOADS("Downloads", "Offline network, storage and notifications"),
    BACKUP_RESTORE("Backup & restore", "Portable OwnPlay settings and personalization"),
    ABOUT("About", "Version and open-source notices"),
}

private enum class ProviderManagementFilter(val label: String) {
    ALL("All"),
    HIDDEN("Hidden"),
    FAVORITES("Favorites"),
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    openSources: Boolean = false,
    onOpenSourcesConsumed: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    var selectedSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSection = selectedSectionName?.let { value ->
        SettingsSection.entries.firstOrNull { it.name == value }
    }

    LaunchedEffect(openSources) {
        if (openSources) {
            selectedSectionName = SettingsSection.SOURCES.name
            onOpenSourcesConsumed()
        }
    }

    BackHandler(enabled = selectedSection != null) {
        selectedSectionName = null
    }

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
            playbackPreferencesRepository = services.playbackPreferencesRepository,
            downloadPreferencesRepository = services.downloadPreferencesRepository,
            backupRestoreRepository = services.backupRestoreRepository,
            section = selectedSection,
            onBack = { selectedSectionName = null },
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsHomeScreen(
    modifier: Modifier,
    onOpen: (SettingsSection) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Settings",
                    color = OwnPlayColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Sources, playback, interface and offline preferences.",
                    color = OwnPlayColors.TextMuted,
                )
            }
        }
        SettingsSection.entries.forEach { section ->
            item(key = section.name) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = OwnPlayShapes.Medium,
                    color = OwnPlayColors.SurfaceRaised,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(section) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                            )
                        }
                        Text(
                            "›",
                            color = OwnPlayColors.TextMuted,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
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
    playbackPreferencesRepository: PlaybackPreferencesRepository,
    downloadPreferencesRepository: DownloadPreferencesRepository,
    backupRestoreRepository: BackupRestoreRepository,
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
    var message by remember { mutableStateOf<String?>(null) }
    var addType by remember { mutableStateOf<SourceType?>(null) }
    var addSubmitting by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var renameSource by remember { mutableStateOf<SourceSummary?>(null) }
    var reconnectSource by remember { mutableStateOf<SourceSummary?>(null) }
    var removeSource by remember { mutableStateOf<SourceSummary?>(null) }

    fun runForSource(source: SourceSummary, block: suspend () -> String) {
        if (source.sourceId.value in busyIds) return
        busyIds = busyIds + source.sourceId.value
        scope.launch {
            try {
                message = block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Could not complete the operation for ${source.displayName}."
            } finally {
                busyIds = busyIds - source.sourceId.value
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("Back to Settings") }
                Text(section.title, color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text(section.summary, color = OwnPlayColors.TextMuted)
            }
        }

        message?.let { status ->
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    shape = OwnPlayShapes.Medium,
                    color = OwnPlayColors.Surface,
                ) {
                    Text(
                        text = status,
                        color = OwnPlayColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (section == SettingsSection.SOURCES) {
            item {
                SettingsSectionCard(
                    title = "Provider sources",
                    subtitle = "Active source: ${activeSource?.displayName ?: "None"}",
                ) {
                    Text(
                        "Add, refresh or reconnect providers without exposing saved credentials.",
                        color = OwnPlayColors.TextSecondary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = {
                                addError = null
                                addSubmitting = false
                                addType = SourceType.XTREAM
                            },
                        ) { Text("Add Xtream") }
                        TextButton(
                            onClick = {
                                addError = null
                                addSubmitting = false
                                addType = SourceType.M3U
                            },
                        ) { Text("Add M3U") }
                        TextButton(
                            enabled = activeSource != null,
                            onClick = {
                                scope.launch {
                                    message = if (repository.clearActiveSource()) {
                                        "No active source selected."
                                    } else {
                                        "Could not clear the active source."
                                    }
                                }
                            },
                        ) { Text("No active source") }
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
                        onSetActive = {
                            runForSource(source) {
                                if (repository.setActiveSource(source.sourceId)) {
                                    "${source.displayName} is now active."
                                } else {
                                    "Could not select ${source.displayName}."
                                }
                            }
                        },
                        onRefresh = {
                            runForSource(source) {
                                when (val result = repository.refreshSource(source.sourceId)) {
                                    SourceRefreshResult.Success -> "${source.displayName} refreshed."
                                    is SourceRefreshResult.Failure ->
                                        result.safeMessage ?: "${source.displayName} refresh failed."
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

        if (section == SettingsSection.LIVE_ORGANIZATION) {
            item {
                LiveOrganizationSettingsSection(
                    source = activeSource,
                    repository = liveOrganizationRepository,
                    onMessage = { message = it },
                )
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

        if (section == SettingsSection.BACKUP_RESTORE) {
            item {
                BackupRestoreSection(
                    repository = backupRestoreRepository,
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

    when (addType) {
        SourceType.XTREAM -> XtreamSourceDialog(
            submitting = addSubmitting,
            errorMessage = addError,
            onDismiss = {
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
                                message = "Xtream source added and catalog imported."
                                addType = null
                            } else {
                                addError = mutationMessage(
                                    result = result,
                                    success = "Xtream source added and catalog imported.",
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            addError = "Could not add the Xtream source."
                        } finally {
                            addSubmitting = false
                        }
                    }
                }
            },
        )
        SourceType.M3U -> M3uSourceDialog(
            submitting = addSubmitting,
            errorMessage = addError,
            onDismiss = {
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
                                message = "M3U source added and catalog imported."
                                addType = null
                            } else {
                                addError = mutationMessage(
                                    result = result,
                                    success = "M3U source added and catalog imported.",
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            addError = "Could not add the M3U source."
                        } finally {
                            addSubmitting = false
                        }
                    }
                }
            },
        )
        null -> Unit
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

    reconnectSource?.let { source ->
        ReconnectSourceDialog(
            source = source,
            onDismiss = { reconnectSource = null },
            onSubmit = { input ->
                runForSource(source) {
                    val result = repository.reconnectSource(source.sourceId, input)
                    if (result is SourceMutationResult.Success) reconnectSource = null
                    mutationMessage(
                        result = result,
                        success = "${source.displayName} reconnected and catalog imported.",
                    )
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
                            if (repository.removeSource(source.sourceId)) {
                                "${source.displayName} removed."
                            } else {
                                "Could not remove ${source.displayName}."
                            }
                        }
                    },
                ) { Text("Remove") }
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
    onSetActive: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember(source.sourceId) { mutableStateOf(false) }
    val authenticationRequired =
        source.refreshFailureCategory == SourceRefreshFailureCategory.AUTHENTICATION
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
                            text = { Text("Remove") },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }

            if (authenticationRequired) {
                Text(
                    "Automatic refresh is suspended until credentials are updated.",
                    color = OwnPlayColors.TextMuted,
                )
                TextButton(enabled = !busy, onClick = onReconnect) { Text("Update credentials") }
            } else if (!source.enabled) {
                Text(
                    "Credentials are not stored in backups. Enter credentials to enable this source.",
                    color = OwnPlayColors.TextMuted,
                )
                TextButton(enabled = !busy, onClick = onReconnect) { Text("Enter credentials") }
            }
        }
    }
}

@Composable
private fun RefreshScheduleSection(
    source: SourceSummary?,
    repository: SourceRefreshScheduleRepository,
    onMessage: (String) -> Unit,
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
                                    if (repository.setSchedule(source.sourceId, option)) {
                                        "Refresh schedule set to ${option.displayName}."
                                    } else {
                                        "Could not update refresh schedule."
                                    },
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
                        if (repository.setWifiOnly(source.sourceId, target)) {
                            if (target) "Wi-Fi-only refresh enabled." else "Wi-Fi-only refresh disabled."
                        } else {
                            "Could not update Wi-Fi-only refresh."
                        },
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
    onMessage: (String) -> Unit,
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
                        if (repository.setShowChannelLogos(target)) {
                            if (target) "Channel logos enabled." else "Channel logos hidden."
                        } else {
                            "Could not update display preference."
                        },
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
                        if (repository.setHideChannelPrefix(target)) {
                            if (target) "OwnPlay channel prefixes hidden." else "Full channel names restored."
                        } else {
                            "Could not update display preference."
                        },
                    )
                }
            },
        )
        SettingsToggleRow(
            title = "Country flags",
            subtitle = "Convert recognized provider country prefixes to flags across Live and Library labels.",
            checked = preferences.showCategoryFlags,
            onCheckedChange = { target ->
                scope.launch {
                    onMessage(
                        if (repository.setShowCategoryFlags(target)) {
                            if (target) "Country flags enabled for provider labels." else "Country flags hidden."
                        } else {
                            "Could not update category-flag preference."
                        },
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
                        if (repository.setHideLiveCategoryPrefix(target)) {
                            if (target) "Live category prefixes hidden." else "Full Live category names restored."
                        } else {
                            "Could not update Live category-label preference."
                        },
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
                        if (repository.setHideLibraryCategoryPrefix(target)) {
                            if (target) "Library category prefixes hidden." else "Full Library category names restored."
                        } else {
                            "Could not update Library category-label preference."
                        },
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
                        if (repository.setPreferTvgName(target)) {
                            if (target) "tvg-name preferred for channel labels." else "Provider display name preferred."
                        } else {
                            "Could not update channel-name preference."
                        },
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
                        if (repository.setCompactMediaRows(target)) {
                            if (target) "Compact media rows enabled." else "Compact media rows disabled."
                        } else {
                            "Could not update display preference."
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun LiveOrganizationSettingsSection(
    source: SourceSummary?,
    repository: LiveOrganizationRepository,
    onMessage: (String) -> Unit,
) {
    var providerManagerOpen by remember(source?.sourceId) { mutableStateOf(false) }

    SettingsSectionCard(
        title = "Live provider",
        subtitle = source?.displayName ?: "No active source",
    ) {
            if (source == null) {
                Text(
                    "Add or select a source to manage provider Live lists.",
                    color = OwnPlayColors.TextMuted,
                )
            } else {
                Text(
                    "Provider identity and membership stay provider-controlled. " +
                        "You can hide and reorder Live entries locally; Reset order restores provider order.",
                    color = OwnPlayColors.TextSecondary,
                )
                TextButton(onClick = { providerManagerOpen = true }) {
                    Text("Manage provider lists")
                }
            }
    }

    val activeSource = source
    if (providerManagerOpen && activeSource != null) {
        ProviderLiveManagementDialog(
            source = activeSource,
            repository = repository,
            onMessage = onMessage,
            onDismiss = { providerManagerOpen = false },
        )
    }
}

@Composable
private fun ProviderLiveManagementDialog(
    source: SourceSummary,
    repository: LiveOrganizationRepository,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val managementFlow = remember(repository, source.sourceId) {
        repository.observeProviderManagement(source.sourceId)
    }
    val management by managementFlow.collectAsState(
        initial = ProviderLiveManagementSnapshot(categories = emptyList(), channels = emptyList()),
    )
    var selectedCategoryId by remember(source.sourceId) { mutableStateOf<String?>(null) }
    var searchQuery by remember(source.sourceId) { mutableStateOf("") }
    var filterName by remember(source.sourceId) { mutableStateOf(ProviderManagementFilter.ALL.name) }
    val filter = ProviderManagementFilter.entries.firstOrNull { it.name == filterName }
        ?: ProviderManagementFilter.ALL
    val selectedCategory = management.categories.firstOrNull { it.categoryId == selectedCategoryId }
    val channels = selectedCategory?.let { category ->
        management.channels.filter { it.categoryId == category.categoryId }
    }.orEmpty()
    val globalManagementMode = searchQuery.isNotBlank() || filter != ProviderManagementFilter.ALL
    val globalChannels = management.channels.filter { channel ->
        val label = channel.localName
            ?: channel.tvgName?.takeIf(String::isNotBlank)
            ?: channel.name
        val matchesSearch = searchQuery.isBlank() || label.contains(searchQuery.trim(), ignoreCase = true)
        val matchesFilter = when (filter) {
            ProviderManagementFilter.ALL -> true
            ProviderManagementFilter.HIDDEN -> channel.hidden
            ProviderManagementFilter.FAVORITES -> channel.favorite
        }
        matchesSearch && matchesFilter
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(selectedCategory?.displayName ?: "Provider categories")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (selectedCategory == null) {
                        "Provider names and grouping stay provider-controlled. Local controls affect visibility and order only."
                    } else {
                        "Channel identity and category membership stay provider-controlled. Local controls affect visibility and order only."
                    },
                    color = OwnPlayColors.TextMuted,
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search channels") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ProviderManagementFilter.entries.forEach { option ->
                        TextButton(
                            enabled = option != filter,
                            onClick = { filterName = option.name },
                        ) { Text(option.label) }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (globalManagementMode) {
                        items(globalChannels, key = { it.channelId }) { channel ->
                            val channelLabel = channel.localName
                                ?: channel.tvgName?.takeIf(String::isNotBlank)
                                ?: channel.name
                            val categoryName = management.categories
                                .firstOrNull { it.categoryId == channel.categoryId }
                                ?.displayName
                                ?: "Provider category"
                            Surface(color = OwnPlayColors.Surface) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        channelLabel,
                                        color = OwnPlayColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(categoryName, color = OwnPlayColors.TextMuted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    if (!repository.setProviderChannelHidden(
                                                            source.sourceId,
                                                            channel.categoryId,
                                                            channel.channelId,
                                                            !channel.hidden,
                                                        )
                                                    ) {
                                                        onMessage("Could not update Provider channel visibility.")
                                                    }
                                                }
                                            },
                                            modifier = Modifier.semantics {
                                                contentDescription =
                                                    if (channel.hidden) "Show $channelLabel" else "Hide $channelLabel"
                                            },
                                        ) { Text(if (channel.hidden) "Show" else "Hide") }
                                        TextButton(
                                            onClick = {
                                                selectedCategoryId = channel.categoryId
                                                searchQuery = ""
                                                filterName = ProviderManagementFilter.ALL.name
                                            },
                                            modifier = Modifier.semantics {
                                                contentDescription =
                                                    "Open $categoryName for $channelLabel"
                                            },
                                        ) { Text("Category") }
                                    }
                                }
                            }
                        }
                    } else if (selectedCategory == null) {
                        itemsIndexed(
                            items = management.categories,
                            key = { _, category -> category.categoryId },
                        ) { index, category ->
                            ProviderManagementRow(
                                title = category.displayName,
                                hidden = category.hidden,
                                onToggleHidden = {
                                    scope.launch {
                                        if (!repository.setProviderCategoryHidden(
                                                source.sourceId,
                                                category.categoryId,
                                                !category.hidden,
                                            )
                                        ) {
                                            onMessage("Could not update Provider category visibility.")
                                        }
                                    }
                                },
                                onMoveUp = if (index > 0) {
                                    {
                                        scope.launch {
                                            val orderedIds = movedProviderIds(
                                                management.categories.map { it.categoryId },
                                                index,
                                                index - 1,
                                            )
                                            if (!repository.setProviderCategoryOrder(source.sourceId, orderedIds)) {
                                                onMessage("Could not update Provider category order.")
                                            }
                                        }
                                    }
                                } else null,
                                onMoveDown = if (index < management.categories.lastIndex) {
                                    {
                                        scope.launch {
                                            val orderedIds = movedProviderIds(
                                                management.categories.map { it.categoryId },
                                                index,
                                                index + 1,
                                            )
                                            if (!repository.setProviderCategoryOrder(source.sourceId, orderedIds)) {
                                                onMessage("Could not update Provider category order.")
                                            }
                                        }
                                    }
                                } else null,
                                onOpen = { selectedCategoryId = category.categoryId },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = channels,
                            key = { _, channel -> channel.channelId },
                        ) { index, channel ->
                            ProviderManagementRow(
                                title = channel.tvgName?.takeIf(String::isNotBlank) ?: channel.name,
                                hidden = channel.hidden,
                                onToggleHidden = {
                                    scope.launch {
                                        if (!repository.setProviderChannelHidden(
                                                source.sourceId,
                                                selectedCategory.categoryId,
                                                channel.channelId,
                                                !channel.hidden,
                                            )
                                        ) {
                                            onMessage("Could not update Provider channel visibility.")
                                        }
                                    }
                                },
                                onMoveUp = if (index > 0) {
                                    {
                                        scope.launch {
                                            val orderedIds = movedProviderIds(
                                                channels.map { it.channelId },
                                                index,
                                                index - 1,
                                            )
                                            if (!repository.setProviderChannelOrder(
                                                    source.sourceId,
                                                    selectedCategory.categoryId,
                                                    orderedIds,
                                                )
                                            ) {
                                                onMessage("Could not update Provider channel order.")
                                            }
                                        }
                                    }
                                } else null,
                                onMoveDown = if (index < channels.lastIndex) {
                                    {
                                        scope.launch {
                                            val orderedIds = movedProviderIds(
                                                channels.map { it.channelId },
                                                index,
                                                index + 1,
                                            )
                                            if (!repository.setProviderChannelOrder(
                                                    source.sourceId,
                                                    selectedCategory.categoryId,
                                                    orderedIds,
                                                )
                                            ) {
                                                onMessage("Could not update Provider channel order.")
                                            }
                                        }
                                    }
                                } else null,
                                onOpen = null,
                            )
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (globalManagementMode) {
                        if (searchQuery.isNotBlank() || filter != ProviderManagementFilter.ALL) {
                            TextButton(
                                onClick = {
                                    searchQuery = ""
                                    filterName = ProviderManagementFilter.ALL.name
                                },
                            ) { Text("Clear") }
                        }
                    } else if (selectedCategory == null) {
                        TextButton(
                            enabled = management.categories.any { it.hidden },
                            onClick = {
                                scope.launch {
                                    if (repository.showAllProviderCategories(source.sourceId)) {
                                        onMessage("All Provider categories are shown.")
                                    } else {
                                        onMessage("Could not show all Provider categories.")
                                    }
                                }
                            },
                        ) { Text("Show all") }
                        TextButton(
                            enabled = management.categories.any { it.manualOrder != null },
                            onClick = {
                                scope.launch {
                                    if (repository.resetProviderCategoryOrder(source.sourceId)) {
                                        onMessage("Provider category order restored.")
                                    } else {
                                        onMessage("Could not reset Provider category order.")
                                    }
                                }
                            },
                        ) { Text("Reset order") }
                    } else {
                        TextButton(onClick = { selectedCategoryId = null }) { Text("Back") }
                        TextButton(
                            enabled = channels.any { it.manualOrder != null },
                            onClick = {
                                scope.launch {
                                    if (repository.resetProviderChannelOrder(
                                            source.sourceId,
                                            selectedCategory.categoryId,
                                        )
                                    ) {
                                        onMessage("Provider channel order restored.")
                                    } else {
                                        onMessage("Could not reset Provider channel order.")
                                    }
                                }
                            },
                        ) { Text("Reset order") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}


private fun movedProviderIds(
    ids: List<String>,
    fromIndex: Int,
    toIndex: Int,
): List<String> {
    if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) return ids
    return ids.toMutableList().apply {
        val moved = removeAt(fromIndex)
        add(toIndex, moved)
    }
}

@Composable
private fun ProviderManagementRow(
    title: String,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onOpen: (() -> Unit)?,
) {
    Surface(
        color = OwnPlayColors.Surface,
        shape = OwnPlayShapes.Medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(
                    onClick = onToggleHidden,
                    modifier = Modifier.semantics {
                        contentDescription = if (hidden) "Show $title" else "Hide $title"
                    },
                ) { Text(if (hidden) "Show" else "Hide") }
                if (onMoveUp != null) {
                    TextButton(
                        onClick = onMoveUp,
                        modifier = Modifier.semantics { contentDescription = "Move $title up" },
                    ) { Text("Up") }
                }
                if (onMoveDown != null) {
                    TextButton(
                        onClick = onMoveDown,
                        modifier = Modifier.semantics { contentDescription = "Move $title down" },
                    ) { Text("Down") }
                }
                if (onOpen != null) {
                    TextButton(
                        onClick = onOpen,
                        modifier = Modifier.semantics {
                            contentDescription = "Manage channels in $title"
                        },
                    ) { Text("Channels") }
                }
            }
        }
    }
}

@Composable
private fun PlaybackSettingsSection(
    repository: PlaybackPreferencesRepository,
    onMessage: (String) -> Unit,
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
                                onMessage("Could not update player volume.")
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
                        if (repository.setAutomaticPictureInPicture(target)) {
                            if (target) "Auto-enter PiP enabled." else "Auto-enter PiP disabled."
                        } else {
                            "Could not update playback preference."
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun DownloadSettingsSection(
    repository: DownloadPreferencesRepository,
    onMessage: (String) -> Unit,
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
                        if (repository.setUnmeteredNetworkOnly(target)) {
                            if (target) "Wi-Fi-only downloads enabled." else "Wi-Fi-only downloads disabled."
                        } else {
                            "Could not update download network preference."
                        },
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
            TextButton(
                onClick = {
                    scope.launch {
                        onMessage(
                            if (repository.setDestinationRelativePath(destination)) {
                                "Download destination updated for new downloads."
                            } else {
                                "Use a valid path under Download/, for example Download/OwnPlay Downloads/."
                            },
                        )
                    }
                },
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
                        if (repository.setNotificationsEnabled(target)) {
                            if (target) {
                                "Download result notifications enabled."
                            } else {
                                "Download result notifications disabled."
                            }
                        } else {
                            "Could not update download notification preference."
                        },
                    )
                }
            },
        )
    }
}

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
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(selected.title) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                ) {
                    item {
                        Text(selected.body, color = OwnPlayColors.TextSecondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { notice = null }) { Text("Close") }
            },
        )
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

@Composable
private fun XtreamSourceDialog(
    submitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (SourceInput.Xtream) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    SourceInputDialog(
        title = "Add Xtream source",
        submitting = submitting,
        errorMessage = errorMessage,
        onDismiss = onDismiss,
        onConfirm = { onSubmit(SourceInput.Xtream(name, server, username, password)) },
    ) {
        OutlinedTextField(
            name,
            { name = it },
            enabled = !submitting,
            label = { Text("Display name") },
            singleLine = true,
        )
        OutlinedTextField(
            server,
            { server = it },
            enabled = !submitting,
            label = { Text("Server URL") },
            singleLine = true,
        )
        OutlinedTextField(
            username,
            { username = it },
            enabled = !submitting,
            label = { Text("Username") },
            singleLine = true,
        )
        OutlinedTextField(
            password,
            { password = it },
            enabled = !submitting,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}

@Composable
private fun M3uSourceDialog(
    submitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (SourceInput.M3u) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var playlist by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    SourceInputDialog(
        title = "Add M3U source",
        submitting = submitting,
        errorMessage = errorMessage,
        onDismiss = onDismiss,
        onConfirm = {
            onSubmit(
                SourceInput.M3u(
                    displayName = name,
                    playlistUrl = playlist,
                    epgUrl = epg.trim().takeIf(String::isNotBlank),
                ),
            )
        },
    ) {
        OutlinedTextField(
            name,
            { name = it },
            enabled = !submitting,
            label = { Text("Display name") },
            singleLine = true,
        )
        OutlinedTextField(
            playlist,
            { playlist = it },
            enabled = !submitting,
            label = { Text("Playlist URL") },
            singleLine = true,
        )
        OutlinedTextField(
            epg,
            { epg = it },
            enabled = !submitting,
            label = { Text("XMLTV / EPG URL (optional)") },
            singleLine = true,
        )
    }
}

@Composable
private fun ReconnectSourceDialog(
    source: SourceSummary,
    onDismiss: () -> Unit,
    onSubmit: (SourceReconnectInput) -> Unit,
) {
    when (source.type) {
        SourceType.XTREAM -> {
            var server by remember(source.sourceId) { mutableStateOf(source.connectionLabel) }
            var username by remember(source.sourceId) { mutableStateOf("") }
            var password by remember(source.sourceId) { mutableStateOf("") }
            SourceInputDialog(
                title = "Reconnect Xtream source",
                onDismiss = onDismiss,
                onConfirm = {
                    onSubmit(SourceReconnectInput.Xtream(server, username, password))
                },
                confirmLabel = "Reconnect",
            ) {
                Text("Saved credentials are never redisplayed. Enter them again to enable this restored source.")
                OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }

        SourceType.M3U -> {
            var playlist by remember(source.sourceId) { mutableStateOf(source.connectionLabel) }
            var epg by remember(source.sourceId) { mutableStateOf("") }
            SourceInputDialog(
                title = "Reconnect M3U source",
                onDismiss = onDismiss,
                onConfirm = {
                    onSubmit(
                        SourceReconnectInput.M3u(
                            playlistUrl = playlist,
                            epgUrl = epg.trim().takeIf(String::isNotBlank),
                        ),
                    )
                },
                confirmLabel = "Reconnect",
            ) {
                Text("Private playlist/EPG parameters are never restored from backup. Enter the full URLs again if required.")
                OutlinedTextField(playlist, { playlist = it }, label = { Text("Playlist URL") }, singleLine = true)
                OutlinedTextField(
                    epg,
                    { epg = it },
                    label = { Text("XMLTV / EPG URL (optional)") },
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun RenameSourceDialog(
    source: SourceSummary,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var name by remember(source.sourceId) { mutableStateOf(source.displayName) }
    SourceInputDialog(
        title = "Rename source",
        onDismiss = onDismiss,
        onConfirm = { onSubmit(name) },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true)
    }
}

@Composable
private fun SourceInputDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "Save",
    submitting: Boolean = false,
    errorMessage: String? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!submitting) onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                if (submitting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Validating source and importing catalog…",
                            color = OwnPlayColors.TextSecondary,
                        )
                    }
                }
                errorMessage?.let { error ->
                    Text(error, color = OwnPlayColors.Error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = onConfirm,
            ) {
                Text(if (submitting) "Saving…" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = onDismiss,
            ) { Text("Cancel") }
        },
    )
}

private fun mutationMessage(
    result: SourceMutationResult,
    success: String,
): String = when (result) {
    is SourceMutationResult.Success -> success
    is SourceMutationResult.Rejected -> when (result.reason) {
        SourceMutationRejection.INVALID_NAME -> "Enter a valid display name."
        SourceMutationRejection.INVALID_CONNECTION -> "Enter a valid source connection."
        SourceMutationRejection.INVALID_CREDENTIALS -> "Enter valid source credentials."
        SourceMutationRejection.DUPLICATE_SOURCE -> "That source connection is already configured."
        SourceMutationRejection.STORAGE_FAILURE -> "The source change could not be saved."
    }
}
