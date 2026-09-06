package app.ownplay.player.source.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivePlaylistStoreTest {
    @Test
    fun persistedEnabledSourceWins() {
        assertEquals(
            "source-b",
            resolveActivePlaylistId(
                persistedSourceId = "source-b",
                currentSourceId = "source-a",
                enabledSourceIds = listOf("source-a", "source-b"),
            ),
        )
    }

    @Test
    fun invalidPersistedSourceFallsBackToCurrentEnabledSource() {
        assertEquals(
            "source-a",
            resolveActivePlaylistId(
                persistedSourceId = "deleted-source",
                currentSourceId = "source-a",
                enabledSourceIds = listOf("source-a", "source-b"),
            ),
        )
    }

    @Test
    fun missingSelectionFallsBackToFirstEnabledSource() {
        assertEquals(
            "source-a",
            resolveActivePlaylistId(
                persistedSourceId = null,
                currentSourceId = null,
                enabledSourceIds = listOf("source-a", "source-b"),
            ),
        )
    }

    @Test
    fun noEnabledSourcesReturnsNull() {
        assertNull(
            resolveActivePlaylistId(
                persistedSourceId = "pending-source",
                currentSourceId = "pending-source",
                enabledSourceIds = emptyList(),
            ),
        )
    }
}
