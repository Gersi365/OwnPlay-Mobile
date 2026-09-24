package app.ownplay.mobile.feature.settings.backup.domain

import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.sources.domain.SourceType
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidationTest {
    @Test
    fun validEnvelopePassesValidation() {
        assertTrue(BackupValidator.validate(envelope()).isEmpty())
    }

    @Test
    fun invalidDownloadDestinationIsRejectedBeforeRestorePlanning() {
        val candidate = envelope(
            payload = OwnPlayBackupPayload(
                sources = listOf(source()),
                globalSettings = BackupGlobalSettings(
                    downloads = DownloadPreferences(destinationRelativePath = "../escape"),
                ),
            ),
        )

        val issues = BackupValidator.validate(candidate)

        assertTrue(
            issues.any {
                it.code == BackupValidationCode.INVALID_FIELD &&
                    it.path == "$.payload.globalSettings.downloads.destinationRelativePath"
            },
        )
    }

    @Test
    fun blankPersonalizationIdentityIsRejected() {
        val candidate = envelope(
            payload = OwnPlayBackupPayload(
                sources = listOf(source()),
                channelPersonalization = listOf(
                    BackupChannelPersonalization(
                        sourceId = "source-1",
                        channelId = "",
                        favorite = true,
                        hidden = false,
                        localName = null,
                        localLogo = null,
                        manualOrder = null,
                    ),
                ),
            ),
        )

        val issues = BackupValidator.validate(candidate)

        assertTrue(
            issues.any {
                it.code == BackupValidationCode.INVALID_FIELD &&
                    it.path == "$.payload.channelPersonalization[].channelId"
            },
        )
    }

    @Test
    fun sourceLocatorWithSecretQueryIsRejected() {
        val candidate = envelope(
            payload = OwnPlayBackupPayload(
                sources = listOf(
                    source(baseLocator = "https://example.com/portal?token=secret"),
                ),
            ),
        )

        val issues = BackupValidator.validate(candidate)

        assertTrue(issues.any { it.code == BackupValidationCode.INVALID_FIELD })
    }

    @Test
    fun newerVersionIsRejectedExplicitly() {
        val candidate = OwnPlayBackupEnvelope(
            version = BackupFormatContract.VERSION + 1,
            createdAt = "2026-09-20T00:00:00Z",
            payload = OwnPlayBackupPayload(sources = listOf(source())),
        )

        val issues = BackupValidator.validate(candidate)

        assertTrue(issues.any { it.code == BackupValidationCode.UNSUPPORTED_NEWER_VERSION })
    }

    private fun envelope(
        payload: OwnPlayBackupPayload = OwnPlayBackupPayload(sources = listOf(source())),
    ) = OwnPlayBackupEnvelope(
        createdAt = "2026-09-20T00:00:00Z",
        payload = payload,
    )

    private fun source(
        baseLocator: String = "https://example.com/portal",
    ) = BackupSourceDefinition(
        sourceId = "source-1",
        type = SourceType.XTREAM,
        displayName = "Source",
        baseLocator = baseLocator,
        enabled = true,
    )
}
