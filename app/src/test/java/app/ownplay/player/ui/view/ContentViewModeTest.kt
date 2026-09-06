package app.ownplay.player.ui.view

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentViewModeTest {
    @Test
    fun storedValuesRoundTrip() {
        ContentViewMode.entries.forEach { mode ->
            assertEquals(
                mode,
                ContentViewMode.fromStorageValue(
                    value = mode.storageValue,
                    default = ContentViewMode.LIST,
                ),
            )
        }
    }

    @Test
    fun invalidStoredValueFallsBackToRequestedDefault() {
        assertEquals(
            ContentViewMode.COMPACT,
            ContentViewMode.fromStorageValue(
                value = "not-a-view-mode",
                default = ContentViewMode.COMPACT,
            ),
        )
    }

    @Test
    fun missingStoredValueFallsBackToRequestedDefault() {
        assertEquals(
            ContentViewMode.CARDS,
            ContentViewMode.fromStorageValue(
                value = null,
                default = ContentViewMode.CARDS,
            ),
        )
    }
}
