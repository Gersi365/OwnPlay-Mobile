package app.ownplay.mobile.downloads.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDownloadMediaTest {
    @Test
    fun diagnosticsNeverExposeAuthenticatedUri() {
        val secret = "https://example.invalid/movie/user/password/7.mp4"
        val media = ResolvedDownloadMedia(
            uri = secret,
            extension = "mp4",
            displayName = "Movie.mp4",
            relativeDirectories = listOf("Movies"),
        )
        assertFalse(media.toString().contains(secret))
        assertTrue(media.toString().contains("<redacted>"))
    }
}
