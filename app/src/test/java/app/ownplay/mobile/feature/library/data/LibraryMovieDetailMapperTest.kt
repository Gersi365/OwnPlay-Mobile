package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.sources.data.xtream.XtreamMovieDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryMovieDetailMapperTest {
    @Test
    fun providerMovieDetailMapsToSafeLibraryMetadata() {
        val metadata = LibraryMovieDetailMapper.metadata(
            XtreamMovieDetail(
                name = "Provider title",
                posterUrl = "https://img.example/poster.jpg",
                backdropUrl = "https://img.example/backdrop.jpg",
                plot = "Plot",
                releaseDate = "2024-05-06",
                year = "2024",
                runtimeMs = 5_400_000L,
                rating = "8.5",
                cast = listOf("Actor One", "Actor Two"),
            ),
        )

        assertEquals("https://img.example/poster.jpg", metadata.posterUrl)
        assertEquals("https://img.example/backdrop.jpg", metadata.backdropUrl)
        assertEquals("Plot", metadata.plot)
        assertEquals("2024-05-06", metadata.releaseDate)
        assertEquals("2024", metadata.year)
        assertEquals(5_400_000L, metadata.runtimeMs)
        assertEquals("8.5", metadata.rating)
        assertEquals(listOf("Actor One", "Actor Two"), metadata.cast)
    }

    @Test
    fun sparseProviderMovieDetailRemainsSparse() {
        val metadata = LibraryMovieDetailMapper.metadata(
            XtreamMovieDetail(
                name = null,
                posterUrl = null,
                backdropUrl = null,
                plot = null,
                releaseDate = null,
                year = null,
                runtimeMs = null,
                rating = null,
            ),
        )

        assertNull(metadata.posterUrl)
        assertNull(metadata.plot)
        assertNull(metadata.runtimeMs)
        assertNull(metadata.rating)
    }
}
