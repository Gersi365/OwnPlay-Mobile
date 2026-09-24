package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.sources.data.xtream.XtreamSeriesDetail
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySeriesDetailMapperTest {
    @Test
    fun providerSeriesMetadataMapsWithoutPersistenceChanges() {
        val metadata = LibrarySeriesDetailMapper.metadata(
            XtreamSeriesDetail(
                episodes = emptyList(),
                posterUrl = "https://img.example/series.jpg",
                backdropUrl = "https://img.example/backdrop.jpg",
                plot = "Series plot",
                releaseDate = "2025-01-02",
                year = "2025",
                genre = "Drama",
                rating = "8.8",
                cast = listOf("Actor One", "Actor Two"),
            ),
        )

        assertEquals("https://img.example/series.jpg", metadata.posterUrl)
        assertEquals("Series plot", metadata.plot)
        assertEquals("2025", metadata.year)
        assertEquals("Drama", metadata.genre)
        assertEquals("8.8", metadata.rating)
        assertEquals(listOf("Actor One", "Actor Two"), metadata.cast)
    }
}
