package app.ownplay.mobile.feature.live.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTransportStreamPlaylistParserTest {
    @Test
    fun parsesNonPrivateTsFixtureAndResolvesSegments() {
        val playlistText = javaClass.classLoader!!
            .getResourceAsStream("fixtures/recordings/live.m3u8")!!
            .bufferedReader()
            .use { it.readText() }
        val playlist = HlsTransportStreamPlaylistParser.parse(
            "https://fixture.invalid/live.m3u8",
            playlistText,
        )

        assertEquals(2, playlist.segments.size)
        assertEquals(
            "https://fixture.invalid/segment-0.ts",
            playlist.segments.first().uri,
        )
        assertEquals(2.0, playlist.segments.first().durationSeconds, 0.0)
        assertEquals(1_790_899_200_000L, playlist.segments.first().programDateTimeEpochMillis)
        assertTrue(playlist.endList)
        listOf("segment-0.ts", "segment-1.ts").forEach { name ->
            val bytes = javaClass.classLoader!!
                .getResourceAsStream("fixtures/recordings/$name")!!
                .use { it.readBytes() }
            assertEquals(376, bytes.size)
            assertEquals(0x47, bytes[0].toInt() and 0xff)
            assertEquals(0x47, bytes[188].toInt() and 0xff)
        }
    }

    @Test
    fun parsesMasterPlaylistBandwidth() {
        val playlist = HlsTransportStreamPlaylistParser.parse(
            "https://fixture.invalid/master.m3u8",
            """#EXTM3U
                |#EXT-X-STREAM-INF:BANDWIDTH=1200000
                |variant/live.m3u8
            """.trimMargin(),
        )

        assertEquals(1_200_000L, playlist.variants.single().bandwidth)
        assertEquals("https://fixture.invalid/variant/live.m3u8", playlist.variants.single().uri)
    }

    @Test
    fun parsesStableMediaSequenceAndDiscontinuityIdentity() {
        val playlist = HlsTransportStreamPlaylistParser.parse(
            "https://fixture.invalid/live.m3u8",
            """#EXTM3U
                |#EXT-X-MEDIA-SEQUENCE:41
                |#EXT-X-DISCONTINUITY-SEQUENCE:2
                |#EXTINF:2,
                |segment-41.ts?token=one
                |#EXT-X-DISCONTINUITY
                |#EXTINF:2,
                |segment-42.ts?token=one
            """.trimMargin(),
        )

        assertEquals(41L, playlist.segments[0].mediaSequence)
        assertEquals(2L, playlist.segments[0].discontinuitySequence)
        assertEquals("sequence:2:41", playlist.segments[0].identity)
        assertEquals(42L, playlist.segments[1].mediaSequence)
        assertEquals(3L, playlist.segments[1].discontinuitySequence)
        assertEquals("sequence:3:42", playlist.segments[1].identity)
    }

    @Test
    fun refusesEncryptedAndFragmentedPlaylists() {
        assertThrows(IOException::class.java) {
            HlsTransportStreamPlaylistParser.parse(
                "https://fixture.invalid/live.m3u8",
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=key.bin\n",
            )
        }
        assertThrows(IOException::class.java) {
            HlsTransportStreamPlaylistParser.parse(
                "https://fixture.invalid/live.m3u8",
                "#EXTM3U\n#EXT-X-MAP:URI=init.mp4\n",
            )
        }
    }
}
