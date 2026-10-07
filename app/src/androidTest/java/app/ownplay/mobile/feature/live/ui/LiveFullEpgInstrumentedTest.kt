package app.ownplay.mobile.feature.live.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.feature.live.domain.LiveProgram
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.sources.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveFullEpgInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingEpgRowOpensItsDetailsAndShowsRecordingStateAccessibly() {
        val sourceId = SourceId("epg-source")
        val now = System.currentTimeMillis() / 1_000L
        val program = LiveProgram("News", now - 300L, now + 600L)
        var selected: LiveProgram? = null
        var actioned: LiveProgram? = null
        compose.setContent {
            MaterialTheme {
                var selectedProgram by remember { mutableStateOf<LiveProgram?>(null) }
                LiveFullEpg(
                    sourceId = sourceId,
                    programs = listOf(program),
                    channelId = "channel",
                    nowEpochSeconds = now,
                    recordings = listOf(
                        LiveRecording(
                            recordingId = "recording",
                            sourceId = sourceId.value,
                            channelId = "channel",
                            channelName = "News channel",
                            title = "News",
                            startEpochSeconds = now - 100L,
                            endEpochSeconds = now + 600L,
                            status = LiveRecordingStatus.RECORDING,
                        ),
                    ),
                    onSelectProgram = { selected = it; selectedProgram = it },
                    onRecordChannel = {},
                )
                selectedProgram?.let {
                    LiveEpgProgramDetailsDialog(
                        program = it,
                        nowEpochSeconds = now,
                        onAction = { actioned = it; selectedProgram = null },
                        onDismiss = { selectedProgram = null },
                    )
                }
            }
        }

        compose.onNodeWithText("Recording").assertExists()
        compose.onNodeWithContentDescription("News. ${formatRangeForTest(program)}. Tap for program details.")
            .performClick()

        assertEquals(program, selected)
        compose.onNodeWithText("This program is on now.").assertExists()
        compose.onNodeWithText("Record now").performClick()
        assertEquals(program, actioned)
    }

    @Test
    fun noEpgOffersAnExplicitAccessibleRecordChannelAction() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                LiveFullEpg(
                    sourceId = SourceId("no-epg-source"),
                    programs = emptyList(),
                    channelId = "channel",
                    nowEpochSeconds = 1_000L,
                    recordings = emptyList(),
                    onSelectProgram = {},
                    onRecordChannel = { calls++ },
                )
            }
        }

        compose.onNodeWithText("Record channel").performClick()
        assertEquals(1, calls)
    }

    @Test
    fun invalidEpgDetailsNeverOfferRecordingActions() {
        val invalid = LiveProgram("Invalid", 2_000L, 1_000L)
        compose.setContent {
            MaterialTheme {
                LiveEpgProgramDetailsDialog(
                    program = invalid,
                    nowEpochSeconds = 1_500L,
                    onAction = { error("Invalid EPG row must not record or schedule") },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("The EPG time window is incomplete or invalid. Recording is unavailable.").assertExists()
        compose.onNodeWithText("Record now").assertDoesNotExist()
        compose.onNodeWithText("Schedule").assertDoesNotExist()
        compose.onNodeWithText("Check catch-up").assertDoesNotExist()
    }

    private fun formatRangeForTest(program: LiveProgram): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).let { formatter ->
            val start = program.startEpochSeconds?.let { formatter.format(java.util.Date(it * 1_000L)) }
            val end = program.endEpochSeconds?.let { formatter.format(java.util.Date(it * 1_000L)) }
            when {
                start != null && end != null -> "$start–$end"
                start != null -> start
                end != null -> end
                else -> ""
            }
        }
}
