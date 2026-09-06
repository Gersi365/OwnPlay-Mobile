package app.ownplay.player.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.PendingImportExecutionTracker
import app.ownplay.player.persistence.PlaylistSourceSummary
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceSyncStage
import app.ownplay.player.source.SourceSyncState
import app.ownplay.player.source.SourceValidator
import app.ownplay.player.source.UrlValidationResult
import app.ownplay.player.source.management.SourceEditSnapshot
import app.ownplay.player.source.management.SourceMutationFailure
import app.ownplay.player.source.management.SourceMutationResult
import app.ownplay.player.source.selection.ActivePlaylistSelection
import app.ownplay.player.source.selection.ActivePlaylistStore
import kotlinx.coroutines.launch

private enum class AddPlaylistMode { XTREAM, REMOTE_M3U, LOCAL_M3U }

@Composable
internal fun PlaylistSettingsScreen(
    runtime: OwnPlayAppRuntime,
    summaries: List<PlaylistSourceSummary>,
    syncState: SourceSyncState,
    onOpenInLive: (String) -> Unit,
) {
    val context = LocalContext.current
    val activePlaylistStore = remember(context) {
        ActivePlaylistStore(context.applicationContext)
    }
    val activePlaylistSelection by activePlaylistStore.observe().collectAsState(
        initial = ActivePlaylistSelection.Loading,
    )
    val activeSourceId =
        (activePlaylistSelection as? ActivePlaylistSelection.Ready)?.sourceId
    val sourceSyncStates by runtime.sourceSyncStates.collectAsState()
    val pendingImportExecution by PendingImportExecutionTracker.state.collectAsState()
    val bannerSyncState = if (
        syncState.stage == SourceSyncStage.LoadingChannels &&
        syncState.sourceId in pendingImportExecution.queuedSourceIds
    ) {
        syncState.copy(stage = SourceSyncStage.Idle)
    } else {
        syncState
    }

    var addMode by remember { mutableStateOf<AddPlaylistMode?>(null) }
    var editSnapshot by remember { mutableStateOf<SourceEditSnapshot?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistSourceSummary?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val configuredCount = summaries.size

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Configured playlists",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$configuredCount configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { addMode = AddPlaylistMode.XTREAM }) {
                Text("Add playlist")
            }
        }

        sourceSyncStatus(bannerSyncState)?.let { status ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (bannerSyncState.stage.isLoading()) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                    Text(
                        text = status,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        actionError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (summaries.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No playlists yet", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Add Xtream or M3U here. Live remains available while the catalog loads.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { addMode = AddPlaylistMode.XTREAM }) {
                        Text("Add playlist")
                    }
                }
            }
        }

        summaries.forEach { summary ->
            PlaylistCard(
                summary = summary,
                syncState = sourceSyncStates[summary.sourceId] ?: SourceSyncState(
                    sourceId = summary.sourceId,
                    sourceName = summary.name,
                ),
                importQueued = summary.sourceId in pendingImportExecution.queuedSourceIds,
                importActive = summary.sourceId in pendingImportExecution.activeSourceIds,
                isActive = summary.enabled && summary.sourceId == activeSourceId,
                onSetActive = {
                    scope.launch {
                        val saved = activePlaylistStore.set(summary.sourceId)
                        if (saved) {
                            runtime.onActiveSourceSelected(summary.sourceId)
                            actionError = null
                        } else {
                            actionError = "Could not save the active playlist."
                        }
                    }
                },
                onOpen = { onOpenInLive(summary.sourceId) },
                onRefresh = {
                    scope.launch {
                        if (summary.enabled) {
                            runtime.refreshSource(summary.sourceId)
                        } else {
                            runtime.retryPendingSource(summary.sourceId)
                        }
                    }
                },
                onEdit = {
                    scope.launch {
                        val loaded = runtime.loadSourceEditSnapshot(summary.sourceId)
                        if (loaded == null) {
                            actionError = "Could not load playlist settings."
                        } else {
                            actionError = null
                            editSnapshot = loaded
                        }
                    }
                },
                onDelete = { deleteTarget = summary },
            )
        }

        HorizontalDivider()
        Text(
            text = "Add source type",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { addMode = AddPlaylistMode.XTREAM },
                modifier = Modifier.weight(1f),
            ) { Text("Xtream") }
            OutlinedButton(
                onClick = { addMode = AddPlaylistMode.REMOTE_M3U },
                modifier = Modifier.weight(1f),
            ) { Text("M3U URL") }
            OutlinedButton(
                onClick = { addMode = AddPlaylistMode.LOCAL_M3U },
                modifier = Modifier.weight(1f),
            ) { Text("File") }
        }
    }

    addMode?.let { mode ->
        AddPlaylistDialog(
            mode = mode,
            runtime = runtime,
            onDismiss = { addMode = null },
            onCompleted = {
                addMode = null
                actionError = null
            },
        )
    }

    editSnapshot?.let { snapshot ->
        EditPlaylistDialog(
            snapshot = snapshot,
            runtime = runtime,
            onDismiss = { editSnapshot = null },
            onSaved = {
                editSnapshot = null
                actionError = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    if (target.enabled) {
                        "${target.name} and its imported catalog will be removed from OwnPlay."
                    } else {
                        "${target.name} will be removed and any pending import will be cancelled."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            when (val result = runtime.deleteSource(target.sourceId)) {
                                SourceMutationResult.Success -> {
                                    deleteTarget = null
                                    actionError = null
                                }
                                is SourceMutationResult.Failure -> {
                                    deleteTarget = null
                                    actionError = mutationFailureMessage(result.reason)
                                }
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlaylistCard(
    summary: PlaylistSourceSummary,
    syncState: SourceSyncState,
    importQueued: Boolean,
    importActive: Boolean,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val importing = !summary.enabled
    val queued = importing && importQueued
    val activelyImporting = importing && importActive
    val syncing = if (importing) activelyImporting else syncState.stage.isLoading()
    val busy = if (importing) queued || activelyImporting else syncing
    val importFailed =
        importing && !queued && !activelyImporting && syncState.stage == SourceSyncStage.ChannelsFailed
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = summary.name.trim().take(1).ifBlank { "P" }.uppercase(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = summary.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            activelyImporting -> "${sourceKindLabel(summary.sourceKind)} • Importing…"
                            queued -> "${sourceKindLabel(summary.sourceKind)} • Waiting to import…"
                            importFailed -> "${sourceKindLabel(summary.sourceKind)} • Import failed"
                            importing -> "${sourceKindLabel(summary.sourceKind)} • Waiting to import…"
                            else -> "${sourceKindLabel(summary.sourceKind)} • ${summary.channelCount} channels"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (syncing) CircularProgressIndicator(strokeWidth = 2.dp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isActive,
                        enabled = summary.enabled,
                        role = Role.RadioButton,
                        onClick = onSetActive,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(
                    selected = isActive,
                    onClick = null,
                    enabled = summary.enabled,
                )
                Text(
                    text = when {
                        activelyImporting -> "Import in progress"
                        queued -> "Queued for import"
                        importFailed -> "Retry import before activating"
                        importing -> "Available after import"
                        else -> "Use as active playlist"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (summary.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onOpen, enabled = summary.enabled) { Text("Live") }
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text(if (summary.enabled) "Refresh" else "Retry")
                }
                TextButton(onClick = onEdit, enabled = summary.enabled && !syncing) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun AddPlaylistDialog(
    mode: AddPlaylistMode,
    runtime: OwnPlayAppRuntime,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember(mode) { mutableStateOf("") }
    var endpoint by remember(mode) { mutableStateOf("") }
    var username by remember(mode) { mutableStateOf("") }
    var password by remember(mode) { mutableStateOf("") }
    var allowCleartext by remember(mode) { mutableStateOf(false) }
    var localUri by remember(mode) { mutableStateOf<String?>(null) }
    var error by remember(mode) { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val retained = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            if (retained) {
                localUri = uri.toString()
                if (name.isBlank()) name = "Local playlist"
            } else {
                error = "OwnPlay could not retain access to this file."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add playlist") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = when (mode) {
                        AddPlaylistMode.XTREAM -> "Xtream"
                        AddPlaylistMode.REMOTE_M3U -> "M3U URL"
                        AddPlaylistMode.LOCAL_M3U -> "Local M3U"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (mode) {
                    AddPlaylistMode.XTREAM -> {
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("Server URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowCleartext,
                                onCheckedChange = { allowCleartext = it },
                            )
                            Text("Allow HTTP for this provider")
                        }
                        if (allowCleartext) {
                            Text(
                                text = "HTTP does not encrypt Xtream credentials or stream traffic.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    AddPlaylistMode.REMOTE_M3U -> {
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("Playlist URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowCleartext,
                                onCheckedChange = { allowCleartext = it },
                            )
                            Text("Allow HTTP for this playlist and EPG")
                        }
                        if (allowCleartext) {
                            Text(
                                text = "HTTP does not encrypt playlist, EPG, or stream traffic.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    AddPlaylistMode.LOCAL_M3U -> {
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (localUri == null) "Choose file" else "File selected")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowCleartext,
                                onCheckedChange = { allowCleartext = it },
                            )
                            Text("Allow HTTP EPG links from this file")
                        }
                        if (allowCleartext) {
                            Text(
                                text = "HTTP EPG traffic is not encrypted.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    text = "After you tap Add, this form closes immediately. Channel and EPG import continue in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validationError = validateAddPlaylistInput(
                        mode = mode,
                        name = name,
                        endpoint = endpoint,
                        username = username,
                        password = password,
                        allowCleartext = allowCleartext,
                        localUri = localUri,
                    )
                    if (validationError != null) {
                        error = validationError
                        return@Button
                    }

                    when (mode) {
                        AddPlaylistMode.XTREAM -> SourceSubmissionCoordinator.submitXtream(
                            runtime = runtime,
                            name = name,
                            serverUrl = endpoint,
                            username = username,
                            password = password,
                            allowCleartext = allowCleartext,
                        )
                        AddPlaylistMode.REMOTE_M3U -> SourceSubmissionCoordinator.submitRemoteM3u(
                            runtime = runtime,
                            name = name,
                            playlistUrl = endpoint,
                            allowCleartext = allowCleartext,
                        )
                        AddPlaylistMode.LOCAL_M3U -> SourceSubmissionCoordinator.submitLocalM3u(
                            runtime = runtime,
                            name = name,
                            documentUri = checkNotNull(localUri),
                            allowCleartext = allowCleartext,
                        )
                    }
                    onCompleted()
                },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditPlaylistDialog(
    snapshot: SourceEditSnapshot,
    runtime: OwnPlayAppRuntime,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(snapshot) { mutableStateOf(snapshot.name) }
    var endpoint by remember(snapshot) { mutableStateOf(snapshot.endpoint.orEmpty()) }
    var username by remember(snapshot) { mutableStateOf("") }
    var password by remember(snapshot) { mutableStateOf("") }
    var allowCleartext by remember(snapshot) { mutableStateOf(snapshot.allowCleartext) }
    var working by remember(snapshot) { mutableStateOf(false) }
    var error by remember(snapshot) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Edit playlist") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (snapshot.sourceKind == SourceKinds.XTREAM) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("Server URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Leave new credentials empty to keep the existing username/password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("New username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("New password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allowCleartext,
                            onCheckedChange = { allowCleartext = it },
                        )
                        Text("Allow HTTP for this provider")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (working) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text("Saving and refreshing…")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    error = null
                    scope.launch {
                        val result = if (snapshot.sourceKind == SourceKinds.XTREAM) {
                            runtime.updateXtreamSource(
                                sourceId = snapshot.sourceId,
                                name = name,
                                serverUrl = endpoint,
                                replacementUsername = username,
                                replacementPassword = password,
                                allowCleartext = allowCleartext,
                            )
                        } else {
                            runtime.renameSource(snapshot.sourceId, name)
                        }
                        when (result) {
                            SourceMutationResult.Success -> onSaved()
                            is SourceMutationResult.Failure -> {
                                working = false
                                error = mutationFailureMessage(result.reason)
                            }
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
        },
    )
}

private fun validateAddPlaylistInput(
    mode: AddPlaylistMode,
    name: String,
    endpoint: String,
    username: String,
    password: String,
    allowCleartext: Boolean,
    localUri: String?,
): String? {
    if (name.trim().isEmpty()) return "Enter a playlist name."

    return when (mode) {
        AddPlaylistMode.XTREAM -> {
            when (val validation = SourceValidator.validateXtreamServer(endpoint)) {
                is UrlValidationResult.Invalid -> sourceErrorMessage(validation.error)
                is UrlValidationResult.Valid -> when {
                    validation.usesCleartext && !allowCleartext ->
                        sourceErrorMessage(SourceError.CleartextTransportRequiresOptIn)
                    username.trim().isEmpty() || password.isEmpty() ->
                        sourceErrorMessage(SourceError.InvalidCredentials)
                    else -> null
                }
            }
        }
        AddPlaylistMode.REMOTE_M3U -> {
            when (val validation = SourceValidator.validateRemotePlaylistUrl(endpoint)) {
                is UrlValidationResult.Invalid -> sourceErrorMessage(validation.error)
                is UrlValidationResult.Valid -> when {
                    validation.usesCleartext && !allowCleartext ->
                        sourceErrorMessage(SourceError.CleartextTransportRequiresOptIn)
                    else -> null
                }
            }
        }
        AddPlaylistMode.LOCAL_M3U -> {
            val uri = localUri ?: return "Choose a local M3U file."
            SourceValidator.validateLocalDocumentUri(uri)?.let(::sourceErrorMessage)
        }
    }
}

private fun SourceSyncStage.isLoading(): Boolean =
    this == SourceSyncStage.LoadingChannels || this == SourceSyncStage.LoadingEpg

private fun sourceKindLabel(kind: String): String = when (kind) {
    SourceKinds.XTREAM -> "Xtream"
    SourceKinds.REMOTE_M3U -> "M3U URL"
    SourceKinds.LOCAL_M3U -> "Local M3U"
    else -> "Playlist"
}

private fun mutationFailureMessage(failure: SourceMutationFailure): String = when (failure) {
    SourceMutationFailure.NotFound -> "Playlist no longer exists."
    SourceMutationFailure.InvalidName -> "Enter a playlist name."
    SourceMutationFailure.UnsupportedEdit -> "This playlist type only supports renaming for now."
    SourceMutationFailure.IncompleteCredentialReplacement -> "Enter both new username and password, or leave both empty."
    SourceMutationFailure.SecureStorageFailure -> "Secure storage failed."
    SourceMutationFailure.PersistenceFailure -> "Could not save playlist changes."
    is SourceMutationFailure.SourceFailure -> sourceErrorMessage(failure.error)
}