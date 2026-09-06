package app.ownplay.player.playback

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTracksTest {
    private val audio = PlaybackTrackOption(
        id = "audio-1",
        kind = PlaybackTrackKind.AUDIO,
        label = "English",
        language = "en",
        selectedByPlayer = true,
        supported = true,
    )
    private val subtitle = PlaybackTrackOption(
        id = "subtitle-1",
        kind = PlaybackTrackKind.SUBTITLE,
        label = "English CC",
        language = "en",
        selectedByPlayer = false,
        supported = true,
    )

    @Test
    fun specificSelectionsRequireKnownSupportedEphemeralIds() {
        val state = PlaybackTrackState(
            audioTracks = listOf(audio),
            subtitleTracks = listOf(subtitle),
        )

        val selectedAudio = PlaybackTrackSelectionPolicy.selectAudio(
            state,
            PlaybackAudioSelection.Specific(audio.id),
        )
        assertEquals(
            PlaybackAudioSelection.Specific(audio.id),
            selectedAudio.audioSelection,
        )

        val staleAudio = PlaybackTrackSelectionPolicy.selectAudio(
            selectedAudio,
            PlaybackAudioSelection.Specific("stale-audio"),
        )
        assertTrue(staleAudio === selectedAudio)

        val selectedSubtitle = PlaybackTrackSelectionPolicy.selectSubtitle(
            state,
            PlaybackSubtitleSelection.Specific(subtitle.id),
        )
        assertEquals(
            PlaybackSubtitleSelection.Specific(subtitle.id),
            selectedSubtitle.subtitleSelection,
        )

        val staleSubtitle = PlaybackTrackSelectionPolicy.selectSubtitle(
            selectedSubtitle,
            PlaybackSubtitleSelection.Specific("stale-subtitle"),
        )
        assertTrue(staleSubtitle === selectedSubtitle)
    }

    @Test
    fun subtitleOffAndDefaultAreExplicitSessionSelections() {
        val state = PlaybackTrackState(subtitleTracks = listOf(subtitle))

        val off = PlaybackTrackSelectionPolicy.selectSubtitle(
            state,
            PlaybackSubtitleSelection.Off,
        )
        assertEquals(PlaybackSubtitleSelection.Off, off.subtitleSelection)

        val default = PlaybackTrackSelectionPolicy.selectSubtitle(
            off,
            PlaybackSubtitleSelection.Default,
        )
        assertEquals(PlaybackSubtitleSelection.Default, default.subtitleSelection)
    }

    @Test
    fun newMediaResetPreventsSelectionIntentFromLeakingAcrossChannels() {
        val selected = PlaybackTrackState(
            audioTracks = listOf(audio),
            subtitleTracks = listOf(subtitle),
            audioSelection = PlaybackAudioSelection.Specific(audio.id),
            subtitleSelection = PlaybackSubtitleSelection.Off,
        )

        val reset = PlaybackTrackSelectionPolicy.resetForNewMedia()

        assertTrue(reset.audioTracks.isEmpty())
        assertTrue(reset.subtitleTracks.isEmpty())
        assertEquals(PlaybackAudioSelection.Default, reset.audioSelection)
        assertEquals(PlaybackSubtitleSelection.Default, reset.subtitleSelection)
        assertFalse(reset == selected)
    }

    @Test
    fun playerFailureResetKeepsDiagnosticsButDropsEphemeralTrackState() {
        val diagnostics = PlaybackDiagnostics(
            videoMimeType = "video/avc",
            videoDecoder = "c2.vendor.decoder",
            videoWidth = 1920,
            videoHeight = 1080,
        )
        val selected = PlaybackTrackState(
            audioTracks = listOf(audio),
            subtitleTracks = listOf(subtitle),
            audioSelection = PlaybackAudioSelection.Specific(audio.id),
            subtitleSelection = PlaybackSubtitleSelection.Off,
            diagnostics = diagnostics,
        )

        val reset = PlaybackTrackSelectionPolicy.resetAfterPlayerFailure(selected)

        assertTrue(reset.audioTracks.isEmpty())
        assertTrue(reset.subtitleTracks.isEmpty())
        assertEquals(PlaybackAudioSelection.Default, reset.audioSelection)
        assertEquals(PlaybackSubtitleSelection.Default, reset.subtitleSelection)
        assertEquals(diagnostics, reset.diagnostics)
    }

    @Test
    fun trackRefreshDropsSpecificSelectionWhenItsHandleDisappears() {
        val selected = PlaybackTrackState(
            audioTracks = listOf(audio),
            subtitleTracks = listOf(subtitle),
            audioSelection = PlaybackAudioSelection.Specific(audio.id),
            subtitleSelection = PlaybackSubtitleSelection.Specific(subtitle.id),
        )

        val refreshed = PlaybackTrackSelectionPolicy.withTracks(
            state = selected,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
        )

        assertEquals(PlaybackAudioSelection.Default, refreshed.audioSelection)
        assertEquals(PlaybackSubtitleSelection.Default, refreshed.subtitleSelection)
    }

    @Test
    fun labelsUseMetadataThenDeterministicFallbackAndRejectSensitiveLookingValues() {
        assertEquals(
            "English · en",
            PlaybackTrackLabelFormatter.format(
                kind = PlaybackTrackKind.AUDIO,
                rawLabel = "  English  ",
                rawLanguage = "en",
                ordinal = 1,
            ),
        )
        assertEquals(
            "Subtitle 2",
            PlaybackTrackLabelFormatter.format(
                kind = PlaybackTrackKind.SUBTITLE,
                rawLabel = "https://example.test/live?token=secret",
                rawLanguage = null,
                ordinal = 2,
            ),
        )
        assertEquals(
            "Audio 3",
            PlaybackTrackLabelFormatter.format(
                kind = PlaybackTrackKind.AUDIO,
                rawLabel = null,
                rawLanguage = null,
                ordinal = 3,
            ),
        )
    }

    @Test
    fun sensitiveMetadataDetectionDoesNotDependOnDeviceLocale() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(
                "Audio 1",
                PlaybackTrackLabelFormatter.format(
                    kind = PlaybackTrackKind.AUDIO,
                    rawLabel = "AUTHORIZATION=secret",
                    rawLanguage = null,
                    ordinal = 1,
                ),
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun modelRenderingRedactsEphemeralIds() {
        val optionText = audio.toString()
        val audioSelectionText = PlaybackAudioSelection.Specific("secret-ish-id").toString()
        val subtitleSelectionText = PlaybackSubtitleSelection.Specific("secret-ish-id").toString()

        assertFalse(optionText.contains(audio.id))
        assertFalse(audioSelectionText.contains("secret-ish-id"))
        assertFalse(subtitleSelectionText.contains("secret-ish-id"))
    }
}
