package com.streamlink.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistFixTest {
    @Test
    fun fixesEqualsInMediaSequence() {
        val bad = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE=26771\n#EXTINF:4.000,\nseg1.ts\n"
        val fixed = String(UnwrapDataSource.fixPlaylist(bad.toByteArray()))
        assertEquals("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:26771\n#EXTINF:4.000,\nseg1.ts\n", fixed)
    }

    @Test
    fun leavesGoodPlaylistsAlone() {
        val good = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n#EXTINF:4,\na.ts\n".toByteArray()
        assertSame(good, UnwrapDataSource.fixPlaylist(good))
    }
}
