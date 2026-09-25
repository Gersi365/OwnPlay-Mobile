package app.ownplay.mobile.feature.settings.backup.data

import app.ownplay.mobile.feature.settings.backup.domain.BackupFormatContract
import app.ownplay.mobile.feature.settings.backup.domain.BackupGlobalSettings
import app.ownplay.mobile.feature.settings.backup.domain.BackupSourceDefinition
import app.ownplay.mobile.feature.settings.backup.domain.BackupValidationCode
import app.ownplay.mobile.feature.settings.backup.domain.OwnPlayBackupEnvelope
import app.ownplay.mobile.feature.settings.backup.domain.OwnPlayBackupPayload
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.sources.domain.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonCodecTest {
    private val codec = BackupJsonCodec()

    @Test
    fun encodeProducesVersionedJsonAndRoundTrips() {
        val envelope = OwnPlayBackupEnvelope(
            createdAt = "2026-09-21T08:30:00Z",
            payload = OwnPlayBackupPayload(
                sources = listOf(
                    BackupSourceDefinition(
                        sourceId = "source-1",
                        type = SourceType.XTREAM,
                        displayName = "Provider",
                        baseLocator = "https://provider.example.com",
                        enabled = true,
                    ),
                ),
                activeSourceId = "source-1",
                globalSettings = BackupGlobalSettings(
                    display = DisplayPreferences(
                        compactMediaRows = true,
                        showChannelLogos = false,
                        preferTvgName = true,
                        hideChannelPrefix = false,
                        showCategoryFlags = false,
                        hideLiveCategoryPrefix = true,
                        hideLibraryCategoryPrefix = false,
                    ),
                ),
            ),
        )

        val bytes = codec.encode(envelope)
        val raw = bytes.toString(Charsets.UTF_8)

        assertTrue(raw.trimStart().startsWith("{"))
        assertTrue(raw.contains("\"format\":\"ownplay-backup\""))
        assertTrue(raw.contains("\"version\":1"))
        assertTrue(raw.contains("\"payload\""))
        assertTrue(raw.contains("\"showCategoryFlags\":false"))
        assertTrue(raw.contains("\"hideLiveCategoryPrefix\":true"))
        assertTrue(raw.contains("\"hideLibraryCategoryPrefix\":false"))
        assertFalse(raw.contains("\"password\""))
        assertFalse(raw.contains("\"username\""))
        assertFalse(raw.contains("credentialReference"))

        val decoded = codec.decode(bytes)
        assertTrue(decoded is BackupJsonDecodeResult.Success)
        assertEquals(envelope, (decoded as BackupJsonDecodeResult.Success).envelope)
    }

    @Test
    fun legacyRotateToFullscreenFieldIsIgnoredAndNotReEmitted() {
        val raw =
            """
            {
              "format": "ownplay-backup",
              "version": 1,
              "createdAt": "2026-09-21T08:30:00Z",
              "payload": {
                "sources": [],
                "globalSettings": {
                  "playback": {
                    "automaticPictureInPicture": false,
                    "rotateToFullscreen": true,
                    "playerVolume": 0.5
                  }
                }
              }
            }
            """.trimIndent()

        val decoded = codec.decode(raw.toByteArray())

        assertTrue(decoded is BackupJsonDecodeResult.Success)
        val envelope = (decoded as BackupJsonDecodeResult.Success).envelope
        assertFalse(envelope.payload.globalSettings.playback.automaticPictureInPicture)
        assertEquals(0.5f, envelope.payload.globalSettings.playback.playerVolume)
        assertFalse(codec.encode(envelope).toString(Charsets.UTF_8).contains("rotateToFullscreen"))
    }

    @Test
    fun legacyHideCategoryPrefixAppliesToLiveAndLibrary() {
        val raw =
            """
            {
              "format": "ownplay-backup",
              "version": 1,
              "createdAt": "2026-09-21T08:30:00Z",
              "payload": {
                "sources": [],
                "globalSettings": {
                  "display": {
                    "hideCategoryPrefix": true
                  }
                }
              }
            }
            """.trimIndent()

        val decoded = codec.decode(raw.toByteArray())

        assertTrue(decoded is BackupJsonDecodeResult.Success)
        val display = (decoded as BackupJsonDecodeResult.Success)
            .envelope.payload.globalSettings.display
        assertTrue(display.hideLiveCategoryPrefix)
        assertTrue(display.hideLibraryCategoryPrefix)
        assertTrue(display.showCategoryFlags)
    }

    @Test
    fun decodeRejectsForbiddenSecretField() {
        val raw =
            """
            {
              "format": "ownplay-backup",
              "version": 1,
              "createdAt": "2026-09-21T08:30:00Z",
              "payload": {
                "sources": [],
                "password": "must-not-import"
              }
            }
            """.trimIndent()

        val decoded = codec.decode(raw.toByteArray())

        assertTrue(decoded is BackupJsonDecodeResult.Failure)
        val issues = (decoded as BackupJsonDecodeResult.Failure).issues
        assertTrue(issues.any { it.code == BackupValidationCode.FORBIDDEN_SECRET_FIELD })
    }

    @Test
    fun decodeRejectsNewerVersion() {
        val raw =
            """
            {
              "format": "${BackupFormatContract.FORMAT}",
              "version": ${BackupFormatContract.VERSION + 1},
              "createdAt": "2026-09-21T08:30:00Z",
              "payload": {}
            }
            """.trimIndent()

        val decoded = codec.decode(raw.toByteArray())

        assertTrue(decoded is BackupJsonDecodeResult.Failure)
        val issues = (decoded as BackupJsonDecodeResult.Failure).issues
        assertTrue(issues.any { it.code == BackupValidationCode.UNSUPPORTED_NEWER_VERSION })
    }
}
