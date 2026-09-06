package app.ownplay.player.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationBackupContractTest {
    @Test
    fun `V1 round trip preserves supported personalization without secure values`() {
        val identity = BackupChannelIdentity(
            providerKey = "xtream:live:42",
            sourceKind = "xtream",
            sourceName = "Home",
            sourceId = "source-home",
        )
        val backup = PersonalizationBackupV1(
            createdAtEpochMillis = 1234L,
            channels = listOf(
                BackupChannelRecord(
                    identity = identity,
                    localDisplayName = "News",
                    manualOrder = 3L,
                    hiddenAtEpochMillis = 100L,
                    favoriteOrder = 2L,
                    favoriteAddedAtEpochMillis = 90L,
                    logoOverrideOmitted = true,
                ),
            ),
            groups = listOf(
                BackupGroupRecord(
                    groupId = "group-1",
                    name = "Family",
                    groupOrder = 0L,
                    createdAtEpochMillis = 50L,
                    members = listOf(BackupGroupMember(identity = identity, groupOrder = 0L)),
                ),
            ),
        )

        val encoded = PersonalizationBackupCodec.encode(backup)
        val decoded = PersonalizationBackupCodec.decode(encoded)

        assertEquals(BackupDecodeResult.Success(backup), decoded)
        assertFalse(encoded.contains("credentialRef"))
        assertFalse(encoded.contains("locatorRef"))
        assertFalse(encoded.contains("logoOverrideRef"))
        assertTrue(encoded.contains("logoOverrideOmitted"))
        assertTrue(encoded.contains("source-home"))
    }

    @Test
    fun `invalid json and unsupported format are rejected before restore planning`() {
        assertEquals(
            BackupDecodeResult.Failure(BackupDecodeFailureReason.INVALID_JSON),
            PersonalizationBackupCodec.decode("{not-json"),
        )
        assertEquals(
            BackupDecodeResult.Failure(BackupDecodeFailureReason.UNSUPPORTED_FORMAT),
            PersonalizationBackupCodec.decode(
                """{"format":"other.product","version":1,"createdAtEpochMillis":1,"channels":[],"groups":[]}""",
            ),
        )
    }

    @Test
    fun `unsupported version is rejected before restore planning`() {
        val raw =
            """{"format":"ownplay.personalization","version":2,"createdAtEpochMillis":1,"channels":[],"groups":[]}"""

        assertEquals(
            BackupDecodeResult.Failure(BackupDecodeFailureReason.UNSUPPORTED_VERSION),
            PersonalizationBackupCodec.decode(raw),
        )
    }

    @Test
    fun `duplicate stable channel identities are rejected`() {
        val raw =
            """{"format":"ownplay.personalization","version":1,"createdAtEpochMillis":1,"channels":[{"identity":{"providerKey":"xtream:live:1","sourceKind":"xtream","sourceName":"Home","sourceId":"source"},"localDisplayName":"One","manualOrder":null,"hiddenAtEpochMillis":null,"favoriteOrder":null,"favoriteAddedAtEpochMillis":null,"logoOverrideOmitted":false},{"identity":{"providerKey":"xtream:live:1","sourceKind":"xtream","sourceName":"Home","sourceId":"source"},"localDisplayName":"Two","manualOrder":null,"hiddenAtEpochMillis":null,"favoriteOrder":null,"favoriteAddedAtEpochMillis":null,"logoOverrideOmitted":false}],"groups":[]}"""

        assertEquals(
            BackupDecodeResult.Failure(BackupDecodeFailureReason.INVALID_PAYLOAD),
            PersonalizationBackupCodec.decode(raw),
        )
    }

    @Test
    fun `invalid favorite pair is rejected`() {
        val raw =
            """{"format":"ownplay.personalization","version":1,"createdAtEpochMillis":1,"channels":[{"identity":{"providerKey":"xtream:live:1","sourceKind":"xtream","sourceName":"Home"},"localDisplayName":null,"manualOrder":null,"hiddenAtEpochMillis":null,"favoriteOrder":0,"favoriteAddedAtEpochMillis":null,"logoOverrideOmitted":false}],"groups":[]}"""

        assertEquals(
            BackupDecodeResult.Failure(BackupDecodeFailureReason.INVALID_PAYLOAD),
            PersonalizationBackupCodec.decode(raw),
        )
    }

    @Test
    fun `matcher uses provider identity and source hints instead of exported channel id`() {
        val identity = BackupChannelIdentity(
            providerKey = "xtream:live:7",
            sourceKind = "xtream",
            sourceName = "Living room",
            sourceId = "old-source-id",
        )
        val candidates = listOf(
            RestoreChannelCandidate(
                channelId = "new-local-id",
                sourceId = "new-source-id",
                providerKey = "xtream:live:7",
                sourceKind = "xtream",
                sourceName = "Living room",
            ),
            RestoreChannelCandidate(
                channelId = "other-local-id",
                sourceId = "other-source-id",
                providerKey = "xtream:live:7",
                sourceKind = "xtream",
                sourceName = "Bedroom",
            ),
        )

        assertEquals(
            BackupChannelMatch.Matched(candidates.first()),
            BackupChannelMatcher.resolve(identity, candidates),
        )
    }

    @Test
    fun `matcher prefers exact source id when source names collide`() {
        val identity = BackupChannelIdentity(
            providerKey = "xtream:live:8",
            sourceKind = "xtream",
            sourceName = "Home",
            sourceId = "source-b",
        )
        val candidates = listOf(
            RestoreChannelCandidate("a", "source-a", "xtream:live:8", "xtream", "Home"),
            RestoreChannelCandidate("b", "source-b", "xtream:live:8", "xtream", "Home"),
        )

        assertEquals(
            BackupChannelMatch.Matched(candidates.last()),
            BackupChannelMatcher.resolve(identity, candidates),
        )
    }

    @Test
    fun `matcher reports ambiguous and unmatched identities deterministically`() {
        val identity = BackupChannelIdentity(
            providerKey = "m3u:same",
            sourceKind = "remote_m3u",
            sourceName = "Old name",
        )
        val ambiguousCandidates = listOf(
            RestoreChannelCandidate("a", "s1", "m3u:same", "remote_m3u", "One"),
            RestoreChannelCandidate("b", "s2", "m3u:same", "remote_m3u", "Two"),
        )

        assertEquals(
            BackupChannelMatch.Ambiguous,
            BackupChannelMatcher.resolve(identity, ambiguousCandidates),
        )
        assertEquals(
            BackupChannelMatch.Unmatched,
            BackupChannelMatcher.resolve(identity, emptyList()),
        )
    }
}
