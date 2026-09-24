package app.ownplay.mobile.feature.settings.backup.domain

import app.ownplay.mobile.sources.domain.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestorePlannerTest {
    @Test
    fun plannerMergesExistingIdentityAndCreatesUnknownSourceDisabled() {
        val payload = OwnPlayBackupPayload(
            sources = listOf(
                source("source-1", "https://one.example.com"),
                source("source-2", "https://two.example.com"),
            ),
        )
        val plan = BackupRestorePlanner.plan(
            backup = payload,
            existingSources = listOf(
                ExistingBackupSource("source-1", SourceType.XTREAM, "https://one.example.com"),
            ),
        )

        assertFalse(plan.hasConflicts)
        assertEquals(BackupSourceRestoreAction.MERGE_EXISTING, plan.sourceResolutions[0].action)
        assertEquals("source-1", plan.sourceResolutions[0].targetSourceId)
        assertEquals(BackupSourceRestoreAction.CREATE_DISABLED, plan.sourceResolutions[1].action)
        assertEquals("source-2", plan.sourceResolutions[1].targetSourceId)
        assertEquals(2, plan.summary.sourceCount)
    }

    @Test
    fun plannerRejectsMatchingConnectionWhenSourceIdDiffersBecauseStableIdsAreSourceScoped() {
        val plan = BackupRestorePlanner.plan(
            backup = OwnPlayBackupPayload(
                sources = listOf(source("backup-source", "https://same.example.com")),
            ),
            existingSources = listOf(
                ExistingBackupSource("local-source", SourceType.XTREAM, "https://same.example.com"),
            ),
        )

        assertTrue(plan.hasConflicts)
        assertEquals(BackupSourceRestoreAction.CONFLICT, plan.sourceResolutions.single().action)
        assertEquals(null, plan.sourceResolutions.single().targetSourceId)
    }

    @Test
    fun plannerMarksSameIdWithDifferentConnectionAsConflict() {
        val plan = BackupRestorePlanner.plan(
            backup = OwnPlayBackupPayload(
                sources = listOf(source("source-1", "https://backup.example.com")),
            ),
            existingSources = listOf(
                ExistingBackupSource("source-1", SourceType.XTREAM, "https://local.example.com"),
            ),
        )

        assertTrue(plan.hasConflicts)
        assertEquals(BackupSourceRestoreAction.CONFLICT, plan.sourceResolutions.single().action)
        assertEquals(null, plan.sourceResolutions.single().targetSourceId)
    }

    private fun source(
        sourceId: String,
        baseLocator: String,
    ) = BackupSourceDefinition(
        sourceId = sourceId,
        type = SourceType.XTREAM,
        displayName = sourceId,
        baseLocator = baseLocator,
        enabled = true,
    )
}
