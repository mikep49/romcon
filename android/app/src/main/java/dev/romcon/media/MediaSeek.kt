package dev.romcon.media

import dev.romcon.net.MediaState
import dev.romcon.net.Proto

/** Tracks playback between PC reports, including consecutive seeks before the PC answers. */
internal class MediaSeek {
    private var media = MediaState.NONE
    private var updatedAt = 0L

    fun update(media: MediaState, receivedAt: Long) {
        this.media = media
        updatedAt = receivedAt
    }

    fun seekBy(deltaMs: Long, now: Long): Int? {
        val elapsed = if (media.playing) (now - updatedAt).coerceAtLeast(0) * media.ratePercent / 100 else 0
        val position = (media.positionMs + elapsed).coerceIn(0, media.durationMs.coerceAtLeast(0))
        return seekTo(position + deltaMs, now)
    }

    fun seekTo(positionMs: Long, now: Long): Int? {
        if (!media.showing || !media.can(Proto.CAN_SEEK) || media.durationMs <= 0) return null
        // MEDIA_SEEK_TO currently sends a non-negative signed Int in milliseconds.
        val target = positionMs.coerceIn(0, minOf(media.durationMs, Int.MAX_VALUE.toLong()))
        media = media.copy(positionMs = target)
        updatedAt = now
        return target.toInt()
    }
}
