package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.epg.EpgProgram
import app.ownplay.player.epg.EpgSnapshot
import app.ownplay.player.epg.EpgTimelineProjector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpgGuideSheet(
    channelName: String,
    snapshot: EpgSnapshot?,
    loading: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val doneFocusRequester = remember { FocusRequester() }
    val programFocusRequester = remember { FocusRequester() }
    val nowEpochSeconds = System.currentTimeMillis() / 1_000L
    val timeline = remember(snapshot, nowEpochSeconds) {
        EpgTimelineProjector.project(
            programs = snapshot?.programs.orEmpty(),
            nowEpochSeconds = nowEpochSeconds,
        )
    }
    val currentIndex = timeline.current?.let(timeline.programs::indexOf)?.takeIf { it >= 0 }
    val initialFocus = EpgGuideFocusPolicy.initialFocus(
        isTelevision = isTelevision,
        loading = loading,
        failed = failed,
        programCount = timeline.programs.size,
        currentIndex = currentIndex,
    )
    val listState = rememberLazyListState()
    var selectedProgram by remember { mutableStateOf<EpgProgram?>(null) }

    LaunchedEffect(
        isTelevision,
        loading,
        failed,
        timeline.programs.size,
        currentIndex,
    ) {
        when (initialFocus.target) {
            EpgGuideFocusTarget.NONE -> Unit
            EpgGuideFocusTarget.DONE -> {
                withFrameNanos { }
                doneFocusRequester.requestFocus()
            }
            EpgGuideFocusTarget.PROGRAM -> {
                val targetIndex = initialFocus.programIndex ?: return@LaunchedEffect
                listState.scrollToItem((targetIndex - 1).coerceAtLeast(0))
                withFrameNanos { }
                programFocusRequester.requestFocus()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "Full EPG",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isTelevision) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(doneFocusRequester),
                    ) { Text("Done") }
                }
            }

            when {
                loading -> GuideMessage("Updating EPG…")
                failed -> GuideMessage("EPG is unavailable. Live playback remains available.")
                timeline.programs.isEmpty() -> GuideMessage("No guide data is available for this channel.")
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(
                            items = timeline.programs,
                            key = { index, program ->
                                "${program.startEpochSeconds ?: Long.MIN_VALUE}:${program.endEpochSeconds ?: Long.MIN_VALUE}:${program.title}:$index"
                            },
                        ) { index, program ->
                            val previous = timeline.programs.getOrNull(index - 1)
                            val day = program.startEpochSeconds?.let(::localDate)
                            val previousDay = previous?.startEpochSeconds?.let(::localDate)
                            if (day != null && day != previousDay) {
                                DayHeader(day)
                            }
                            ProgramGuideRow(
                                program = program,
                                isCurrent = program == timeline.current,
                                isPast = program in timeline.past,
                                focusRequester = if (
                                    initialFocus.target == EpgGuideFocusTarget.PROGRAM &&
                                    index == initialFocus.programIndex
                                ) {
                                    programFocusRequester
                                } else {
                                    null
                                },
                                onClick = { selectedProgram = program },
                            )
                        }
                    }
                }
            }
        }
    }

    selectedProgram?.let { program ->
        ProgramDetailsDialog(
            program = program,
            onDismiss = { selectedProgram = null },
        )
    }
}

@Composable
private fun GuideMessage(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> DAY_FORMATTER.format(day)
    }
    Text(
        text = label,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProgramGuideRow(
    program: EpgProgram,
    isCurrent: Boolean,
    isPast: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .then(
                focusRequester?.let { requester -> Modifier.focusRequester(requester) } ?: Modifier,
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(
                    text = program.startLabel ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                program.endLabel?.let { end ->
                    Text(
                        text = end,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                        isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                program.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramDetailsDialog(
    program: EpgProgram,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(program.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = timeRange(program),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                program.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(description)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun localDate(epochSeconds: Long): LocalDate =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()

private fun timeRange(program: EpgProgram): String = when {
    program.startLabel != null && program.endLabel != null ->
        "${program.startLabel}–${program.endLabel}"
    program.startLabel != null -> program.startLabel
    else -> "Time unavailable"
}

private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
