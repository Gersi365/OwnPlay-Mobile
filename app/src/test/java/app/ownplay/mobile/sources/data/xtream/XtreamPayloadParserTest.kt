package app.ownplay.mobile.sources.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamPayloadParserTest {
    @Test
    fun `account info preserves provider allowed output format order`() {
        val info = XtreamPayloadParser.accountInfo(
            """{"user_info":{"auth":1,"allowed_output_formats":["m3u8","ts"]}}""",
        )

        assertEquals(listOf("m3u8", "ts"), info.allowedOutputFormats)
        assertEquals(true, info.authenticated)
    }

    @Test
    fun `account info recognizes explicit provider authentication rejection`() {
        val info = XtreamPayloadParser.accountInfo(
            """{"user_info":{"auth":0,"status":"Disabled"}}""",
        )

        assertEquals(false, info.authenticated)
    }

    @Test
    fun `account info exposes provider timezone for catch up timestamps`() {
        val info = XtreamPayloadParser.accountInfo(
            """{"user_info":{"auth":1},"server_info":{"timezone":"Europe/Tirane"}}""",
        )

        assertEquals("Europe/Tirane", info.timezone)
    }

    @Test
    fun `live stream parser exposes provider catch up capability`() {
        val stream = XtreamPayloadParser.liveStreams(
            """[{"stream_id":"42","name":"News","tv_archive":1,"tv_archive_duration":"7"}]""",
        ).single()

        assertEquals(true, stream.catchUpAvailable)
        assertEquals(7, stream.catchUpDurationDays)
    }

    @Test
    fun `epg table uses the same safe decoded listing model as short epg`() {
        val entries = XtreamPayloadParser.epgListings(
            """
            {
              "epg_listings": [
                {
                  "title": "QXJjaGl2ZSBOZXdz",
                  "start_timestamp": "1700000000",
                  "stop_timestamp": "1700003600"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Archive News", entries.single().title)
        assertEquals(1_700_000_000L, entries.single().startEpochSeconds)
        assertEquals(1_700_003_600L, entries.single().endEpochSeconds)
    }

    @Test
    fun `categories preserve provider array order`() {
        val categories = XtreamPayloadParser.categories(
            """
            [
              {"category_id":"20","category_name":"Albania"},
              {"category_id":"10","category_name":"Italia"}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf(0, 1), categories.map { it.providerOrder })
        assertEquals(listOf("20", "10"), categories.map { it.providerCategoryId })
    }

    @Test
    fun `live streams accept numeric stream id and skip incomplete rows`() {
        val streams = XtreamPayloadParser.liveStreams(
            """
            [
              {"stream_id":42,"name":"News HD","category_id":"7","epg_channel_id":"news.al"},
              {"stream_id":43,"category_id":"7"}
            ]
            """.trimIndent(),
        )

        assertEquals(1, streams.size)
        assertEquals("42", streams.single().streamId)
        assertEquals("news.al", streams.single().tvgId)
    }

    @Test
    fun `missing optional movie metadata remains null`() {
        val movies = XtreamPayloadParser.movies(
            """[{"stream_id":"5","name":"Movie"}]""",
        )

        assertEquals(1, movies.size)
        assertNull(movies.single().categoryId)
        assertNull(movies.single().posterUrl)
    }

    @Test
    fun `movie info parses provider metadata without inventing fields`() {
        val detail = XtreamPayloadParser.movieInfo(
            """
            {
              "info": {
                "name": "Provider Movie",
                "movie_image": "https://img.example/poster.jpg",
                "backdrop_path": ["https://img.example/backdrop.jpg"],
                "plot": "Provider plot",
                "releasedate": "2024-05-06",
                "duration_secs": 3723,
                "rating": "8.2"
              },
              "movie_data": {
                "name": "Fallback Movie"
              }
            }
            """.trimIndent(),
        )

        assertEquals("Provider Movie", detail.name)
        assertEquals("https://img.example/poster.jpg", detail.posterUrl)
        assertEquals("https://img.example/backdrop.jpg", detail.backdropUrl)
        assertEquals("Provider plot", detail.plot)
        assertEquals("2024-05-06", detail.releaseDate)
        assertEquals("2024", detail.year)
        assertEquals(3_723_000L, detail.runtimeMs)
        assertEquals("8.2", detail.rating)
    }

    @Test
    fun `movie info uses safe clock duration fallback and leaves absent metadata null`() {
        val detail = XtreamPayloadParser.movieInfo(
            """
            {
              "info": {
                "duration": "01:02:03"
              },
              "movie_data": {
                "name": "Movie"
              }
            }
            """.trimIndent(),
        )

        assertEquals("Movie", detail.name)
        assertEquals(3_723_000L, detail.runtimeMs)
        assertNull(detail.plot)
        assertNull(detail.year)
        assertNull(detail.rating)
    }

    @Test
    fun `series info uses provider episode ids and season keys`() {
        val detail = XtreamPayloadParser.seriesInfo(
            """
            {
              "seasons": [{"season_number": 1}],
              "episodes": {
                "1": [
                  {
                    "id": "1001",
                    "episode_num": 2,
                    "title": "Episode Two",
                    "container_extension": "mkv",
                    "info": {"duration_secs": 2700}
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val episode = detail.episodes.single()
        assertEquals("1001", episode.providerEpisodeId)
        assertEquals(1, episode.seasonNumber)
        assertEquals(2, episode.episodeNumber)
        assertEquals("Episode Two", episode.title)
        assertEquals("mkv", episode.containerExtension)
        assertEquals(2_700_000L, episode.durationMs)
    }

    @Test
    fun `series info skips incomplete episodes instead of inventing identity`() {
        val detail = XtreamPayloadParser.seriesInfo(
            """
            {
              "episodes": {
                "1": [
                  {"episode_num": 1, "title": "Missing id"},
                  {"id": "1002", "title": "Missing number"},
                  {"id": "1003", "episode_num": 3}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<XtreamSeriesEpisode>(), detail.episodes)
    }
    @Test
    fun `short epg decodes provider title and timestamps`() {
        val entries = XtreamPayloadParser.shortEpg(
            """
            {
              "epg_listings": [
                {
                  "title": "TmV3cyAmYW1wOyBXZWF0aGVy",
                  "start_timestamp": "1700000000",
                  "stop_timestamp": 1700003600
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, entries.size)
        assertEquals("News & Weather", entries.single().title)
        assertEquals(1_700_000_000L, entries.single().startEpochSeconds)
        assertEquals(1_700_003_600L, entries.single().endEpochSeconds)
    }


    @Test
    fun movieInfoParsesCastInProviderOrder() {
        val detail = XtreamPayloadParser.movieInfo(
            """
            {
              "info": {
                "cast": "Actor One, Actor Two, Actor One"
              },
              "movie_data": {
                "name": "Movie"
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Actor One", "Actor Two"), detail.cast)
    }

    @Test
    fun seriesInfoParsesDetailMetadataAndCastWithoutInventingValues() {
        val detail = XtreamPayloadParser.seriesInfo(
            """
            {
              "info": {
                "name": "Provider Series",
                "cover": "https://img.example/series.jpg",
                "backdrop_path": ["https://img.example/backdrop.jpg"],
                "plot": "Provider plot",
                "releaseDate": "2025-03-04",
                "genre": "Drama",
                "rating": "9.1",
                "cast": ["Actor One", "Actor Two"]
              },
              "episodes": {}
            }
            """.trimIndent(),
        )

        assertEquals("Provider Series", detail.name)
        assertEquals("https://img.example/series.jpg", detail.posterUrl)
        assertEquals("https://img.example/backdrop.jpg", detail.backdropUrl)
        assertEquals("Provider plot", detail.plot)
        assertEquals("2025-03-04", detail.releaseDate)
        assertEquals("2025", detail.year)
        assertEquals("Drama", detail.genre)
        assertEquals("9.1", detail.rating)
        assertEquals(listOf("Actor One", "Actor Two"), detail.cast)
    }

}
