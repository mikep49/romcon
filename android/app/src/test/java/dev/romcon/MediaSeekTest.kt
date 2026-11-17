package dev.romcon

import dev.romcon.media.MediaSeek
import dev.romcon.net.MediaState
import dev.romcon.net.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSeekTest {
    private val paused = MediaState(
        state = Proto.MEDIA_PAUSED,
        can = Proto.CAN_SEEK,
        positionMs = 30_000,
        durationMs = 120_000,
    )

    @Test
    fun pausedPlaybackDoesNotAdvanceBetweenReports() {
        val seek = MediaSeek().apply { update(paused, 1_000) }
        assertEquals(20_000, seek.seekBy(-10_000, 6_000))
    }

    @Test
    fun playingPositionAdvancesAtThePlayersSpeed() {
        val seek = MediaSeek().apply { update(paused.copy(state = Proto.MEDIA_PLAYING, ratePercent = 150), 1_000) }
        assertEquals(47_500, seek.seekBy(10_000, 6_000))
    }

    @Test
    fun quickTapsAccumulateBeforeThePcAnswers() {
        val seek = MediaSeek().apply { update(paused, 1_000) }
        assertEquals(40_000, seek.seekBy(10_000, 1_100))
        assertEquals(50_000, seek.seekBy(10_000, 1_200))
        assertEquals(40_000, seek.seekBy(-10_000, 1_300))
        assertEquals(30_000, seek.seekBy(-10_000, 1_400))
    }

    @Test
    fun playbackKeepsAdvancingBetweenConsecutiveTaps() {
        val seek = MediaSeek().apply { update(paused.copy(state = Proto.MEDIA_PLAYING), 1_000) }
        assertEquals(41_000, seek.seekBy(10_000, 2_000))
        assertEquals(52_000, seek.seekBy(10_000, 3_000))
    }

    @Test
    fun seeksStayWithinTheTrack() {
        val seek = MediaSeek().apply { update(paused.copy(positionMs = 5_000), 0) }
        assertEquals(0, seek.seekBy(-10_000, 0))
        seek.update(paused.copy(positionMs = 115_000), 0)
        assertEquals(120_000, seek.seekBy(10_000, 0))
        assertEquals(110_000, seek.seekBy(-10_000, 0))
    }

    @Test
    fun rewindingAfterPlaybackReachesTheEndUsesTheTrackEnd() {
        val seek = MediaSeek().apply { update(paused.copy(state = Proto.MEDIA_PLAYING, positionMs = 115_000), 0) }
        assertEquals(110_000, seek.seekBy(-10_000, 20_000))
    }

    @Test
    fun aSeekBarChangeAndNewPcReportsReplaceThePreviousTarget() {
        val seek = MediaSeek().apply { update(paused, 0) }
        assertEquals(80_000, seek.seekTo(80_000, 0))
        assertEquals(90_000, seek.seekBy(10_000, 0))
        seek.update(paused.copy(positionMs = 10_000), 1_000)
        assertEquals(20_000, seek.seekBy(10_000, 1_000))
    }

    @Test
    fun hiddenAndUnseekablePlayersDoNotSendSeekCommands() {
        val seek = MediaSeek()
        assertNull(seek.seekBy(10_000, 0))
        seek.update(paused.copy(can = Proto.CAN_PLAY), 0)
        assertNull(seek.seekBy(10_000, 0))
        seek.update(paused.copy(durationMs = 0), 0)
        assertNull(seek.seekTo(10_000, 0))
        seek.update(MediaState.NONE, 0)
        assertNull(seek.seekBy(-10_000, 0))
    }

    @Test
    fun longTracksStayWithinTheCommandsSignedIntegerRange() {
        val seek = MediaSeek().apply { update(paused.copy(positionMs = Int.MAX_VALUE - 5_000L, durationMs = 4_000_000_000), 0) }
        assertEquals(Int.MAX_VALUE, seek.seekBy(10_000, 0))
    }
}
