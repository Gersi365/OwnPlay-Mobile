package app.ownplay.player.persistence.download

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedMediaStableOrderingContractTest {
    @Test
    fun `downloaded media ordering ignores progress timestamps`() {
        val source = sourceText(
            "src/main/java/app/ownplay/player/persistence/download/DownloadPersistence.kt",
        )

        assertTrue(source.contains("createdAtEpochMillis DESC"))
        assertTrue(source.contains("downloadId ASC"))
        assertFalse(source.contains("updatedAtEpochMillis DESC"))
    }
}
