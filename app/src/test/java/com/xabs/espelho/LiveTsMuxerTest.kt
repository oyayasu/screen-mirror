package com.xabs.espelho

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTsMuxerTest {
    @Test
    fun playlistIsLiveAndMovesWithNewSegments() {
        val mux = LiveTsMuxer()
        mux.configureVideo(byteArrayOf(0, 0, 0, 1, 0x67), byteArrayOf(0, 0, 0, 1, 0x68))
        val idr = byteArrayOf(0, 0, 0, 1, 0x65)
        for (i in 0..12) {
            mux.addVideo(idr, i * 2_000_000L, true)
        }
        val playlist = mux.playlist("/750795")
        assertEquals(6, mux.segmentCount())
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:4\n"))
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:6\n"))
        assertTrue(playlist.contains("#EXT-X-PROGRAM-DATE-TIME:"))
        assertTrue(playlist.contains("/750795/segment-11.ts"))
        assertFalse(playlist.contains("/750795/segment-5.ts"))
        assertFalse(playlist.contains("#EXT-X-ENDLIST"))
        assertFalse(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        val latest = mux.getSegment(11)
        assertNotNull(latest)
        assertTrue(latest!!.isNotEmpty())
        assertEquals(0, latest.size % 188)
        assertEquals(0x47, latest[0].toInt() and 0xff)
        assertEquals(11, mux.latestSegmentId())
    }

    @Test
    fun targetDurationNeverChangesBetweenPlaylistRefreshes() {
        val mux = LiveTsMuxer()
        val before = mux.playlist("/750795")
        mux.addVideo(byteArrayOf(0, 0, 0, 1, 0x65), 0L, true)
        mux.addVideo(byteArrayOf(0, 0, 0, 1, 0x65), 2_100_000L, true)
        val after = mux.playlist("/750795")
        assertTrue(before.contains("#EXT-X-TARGETDURATION:4"))
        assertTrue(after.contains("#EXT-X-TARGETDURATION:4"))
        assertFalse(after.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun rotationMarksVideoDiscontinuityWithoutResettingPlaylist() {
        val mux = LiveTsMuxer()
        val frame = byteArrayOf(0, 0, 0, 1, 0x65)
        mux.configureVideo(byteArrayOf(0, 0, 0, 1, 0x67), byteArrayOf(0, 0, 0, 1, 0x68))
        mux.addVideo(frame, 1_000_000L, true)
        mux.addVideo(frame, 3_000_000L, true)
        mux.videoFormatChanged()
        mux.configureVideo(byteArrayOf(0, 0, 0, 1, 0x67), byteArrayOf(0, 0, 0, 1, 0x68))
        mux.addVideo(frame, 4_000_000L, true)
        mux.addVideo(frame, 6_000_000L, true)
        val live = mux.playlist("/750795")
        assertTrue(live.contains("#EXT-X-DISCONTINUITY-SEQUENCE:0"))
        assertTrue(live.contains("#EXT-X-DISCONTINUITY\\n"))
        assertTrue(live.contains("/750795/segment-2.ts"))
        assertFalse(live.contains("#EXT-X-ENDLIST"))
        assertEquals(2, mux.latestSegmentId())
    }

    @Test
    fun removingOldSegmentsKeepsDiscontinuitySequenceValid() {
        val mux = LiveTsMuxer()
        val frame = byteArrayOf(0, 0, 0, 1, 0x65)
        mux.addVideo(frame, 0L, true)
        mux.addVideo(frame, 2_000_000L, true)
        mux.videoFormatChanged()
        for (i in 2..13) mux.addVideo(frame, i.toLong() * 2_000_000L, true)
        val playlist = mux.playlist("/750795")
        assertTrue(playlist.contains("#EXT-X-DISCONTINUITY-SEQUENCE:1"))
        assertFalse(playlist.contains("#EXT-X-DISCONTINUITY\\n"))
    }
}
