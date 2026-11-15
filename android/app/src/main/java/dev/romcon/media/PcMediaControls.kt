package dev.romcon.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import dev.romcon.MainActivity
import dev.romcon.R
import dev.romcon.net.MediaState
import dev.romcon.net.Proto

/**
 * The phone's side of a PC's media: an Android media session describing what the PC is playing, and the
 * notification that goes with it. Android draws that as media controls on the lock screen and in the shade.
 * Presses come back through [send] as a [Proto.MEDIA_PLAY]-style command and its value.
 *
 * Use from the main thread.
 */
internal class PcMediaControls(private val context: Context, private val send: (command: Int, value: Int) -> Unit) {

    companion object {
        const val ID_STATUS = 1
        const val ID_MEDIA = 2

        const val ACTION_PLAY = "dev.romcon.media.PLAY"
        const val ACTION_PAUSE = "dev.romcon.media.PAUSE"
        const val ACTION_NEXT = "dev.romcon.media.NEXT"
        const val ACTION_PREVIOUS = "dev.romcon.media.PREVIOUS"
        const val ACTION_VOLUME_DOWN = "dev.romcon.media.VOLUME_DOWN"
        const val ACTION_VOLUME_UP = "dev.romcon.media.VOLUME_UP"
        const val ACTION_DISMISSED = "dev.romcon.media.DISMISSED"

        private const val CHANNEL_MEDIA = "media"
        private const val CHANNEL_LINK = "link"

        /** Per press of a button on the notification, in percent of the PC's volume. */
        private const val BUTTON_VOLUME_STEP = 5

        /** Per step of the phone's volume buttons, which repeat while held. */
        private const val KEY_VOLUME_STEP = 2

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MEDIA, "PC media controls", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Play, pause, skip and volume for what a paired PC is playing"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_LINK, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while RomCon stays connected to your PC so the media controls can appear. " +
                        "You can turn this off; the media controls keep working."
                    setShowBadge(false)
                },
            )
        }
    }

    private val session = MediaSession(context, "RomCon PC media")
    private var media = MediaState.NONE
    private var pcName = ""
    private var art: Bitmap? = null
    private var artId = 0
    private var remoteVolume: VolumeProvider? = null

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = send(Proto.MEDIA_PLAY, 0)
                override fun onPause() = send(Proto.MEDIA_PAUSE, 0)
                override fun onSkipToNext() = send(Proto.MEDIA_NEXT, 0)
                override fun onSkipToPrevious() = send(Proto.MEDIA_PREVIOUS, 0)
                override fun onSeekTo(pos: Long) = send(Proto.MEDIA_SEEK_TO, pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                override fun onCustomAction(action: String, extras: Bundle?) {
                    handle(action)
                }
            },
        )
        session.setSessionActivity(openApp())
    }

    /** A press on one of the notification's own buttons, or a custom action. False if [action] isn't one of ours. */
    fun handle(action: String?): Boolean {
        when (action) {
            ACTION_PLAY -> send(Proto.MEDIA_PLAY, 0)
            ACTION_PAUSE -> send(Proto.MEDIA_PAUSE, 0)
            ACTION_NEXT -> send(Proto.MEDIA_NEXT, 0)
            ACTION_PREVIOUS -> send(Proto.MEDIA_PREVIOUS, 0)
            ACTION_VOLUME_DOWN -> send(Proto.MEDIA_ADJUST_VOLUME, -BUTTON_VOLUME_STEP)
            ACTION_VOLUME_UP -> send(Proto.MEDIA_ADJUST_VOLUME, BUTTON_VOLUME_STEP)
            else -> return false
        }
        return true
    }

    /**
     * Puts the controls up, or brings them up to date. [receivedAt] is when the PC's report arrived, in
     * [android.os.SystemClock.elapsedRealtime]; Android moves the position on from there by itself.
     */
    fun show(media: MediaState, pcName: String, receivedAt: Long, volumeKeys: Boolean) {
        this.media = media
        this.pcName = pcName

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artistLine)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (media.durationMs > 0) media.durationMs else -1)
        currentArt?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(metadata.build())

        var actions = PlaybackState.ACTION_PLAY_PAUSE
        if (media.can(Proto.CAN_PLAY)) actions = actions or PlaybackState.ACTION_PLAY
        if (media.can(Proto.CAN_PAUSE)) actions = actions or PlaybackState.ACTION_PAUSE
        if (media.can(Proto.CAN_NEXT)) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (media.can(Proto.CAN_PREVIOUS)) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (media.can(Proto.CAN_SEEK)) actions = actions or PlaybackState.ACTION_SEEK_TO
        val state = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (media.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                if (media.durationMs > 0) media.positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (media.playing) media.ratePercent / 100f else 0f,
                receivedAt,
            )
        if (media.can(Proto.CAN_VOLUME)) {
            // From Android 13 the system draws the buttons from this list; before that, from the notification's actions.
            state.addCustomAction(PlaybackState.CustomAction.Builder(ACTION_VOLUME_DOWN, "PC volume down", R.drawable.ic_volume_down).build())
            state.addCustomAction(PlaybackState.CustomAction.Builder(ACTION_VOLUME_UP, "PC volume up", R.drawable.ic_volume_up).build())
        }
        session.setPlaybackState(state.build())

        setVolume(volumeKeys && media.can(Proto.CAN_VOLUME))
        if (!session.isActive) session.isActive = true
    }

    /** True if the picture belongs to what is showing, so the notification should be redrawn with it. */
    fun setArt(id: Int, bitmap: Bitmap): Boolean {
        art = bitmap
        artId = id
        return media.showing && media.artId == id
    }

    fun hide() {
        if (!media.showing && !session.isActive) return
        media = MediaState.NONE
        session.isActive = false
        session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f).build())
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    /**
     * While the PC plays, Android sends the phone's volume buttons to this session, which passes them to the PC.
     * A paused session doesn't get them, so the buttons go back to the phone's own volume.
     */
    private fun setVolume(remote: Boolean) {
        if (!remote) {
            if (remoteVolume != null) {
                remoteVolume = null
                session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            }
            return
        }
        val provider = remoteVolume ?: object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, 0) {
            override fun onSetVolumeTo(volume: Int) {
                send(Proto.MEDIA_SET_VOLUME, volume.coerceIn(0, 100))
                currentVolume = volume.coerceIn(0, 100)
            }

            override fun onAdjustVolume(direction: Int) {
                // The PC answers with its new volume, which is what moves the slider.
                if (direction != 0) send(Proto.MEDIA_ADJUST_VOLUME, direction * KEY_VOLUME_STEP)
            }
        }.also {
            remoteVolume = it
            session.setPlaybackToRemote(it)
        }
        val volume = if (media.muted) 0 else media.volume
        if (provider.currentVolume != volume) provider.currentVolume = volume
    }

    private val title: String get() = media.title.ifEmpty { "Playing on $pcName" }

    private val artistLine: String get() = media.artist.ifEmpty { pcName }

    /** Null while the picture for this track hasn't arrived, so an old one is never shown against a new title. */
    private val currentArt: Bitmap? get() = art.takeIf { media.artId != 0 && media.artId == artId }

    /** What the notification shows; when this is unchanged, the notification needn't be posted again. */
    fun notificationKey(): Any = listOf(title, artistLine, pcName, media.playing, media.can, currentArt)

    fun notification(): Notification {
        val builder = Notification.Builder(context, CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_romcon)
            .setContentTitle(title)
            .setContentText(artistLine)
            .setSubText(pcName)
            .setLargeIcon(currentArt)
            .setContentIntent(openApp())
            .setDeleteIntent(toService(ACTION_DISMISSED))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(media.playing)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)

        val skip = media.can(Proto.CAN_PREVIOUS) && media.can(Proto.CAN_NEXT)
        val volume = media.can(Proto.CAN_VOLUME)
        val compact = ArrayList<Int>(3)
        var count = 0
        fun add(icon: Int, label: String, action: String, inCompact: Boolean) {
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, icon), label, toService(action)).build())
            if (inCompact) compact += count
            count++
        }
        // The folded notification has room for three: the skip buttons if the player has both, otherwise volume.
        if (media.can(Proto.CAN_PREVIOUS)) add(R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS, skip)
        if (volume && !skip) add(R.drawable.ic_volume_down, "PC volume down", ACTION_VOLUME_DOWN, true)
        if (media.playing) add(R.drawable.ic_media_pause, "Pause", ACTION_PAUSE, true) else add(R.drawable.ic_media_play, "Play", ACTION_PLAY, true)
        if (media.can(Proto.CAN_NEXT)) add(R.drawable.ic_media_next, "Next", ACTION_NEXT, skip)
        if (volume && skip) add(R.drawable.ic_volume_down, "PC volume down", ACTION_VOLUME_DOWN, false)
        if (volume) add(R.drawable.ic_volume_up, "PC volume up", ACTION_VOLUME_UP, !skip)

        builder.style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(*compact.take(3).toIntArray())
        return builder.build()
    }

    /** The quiet notification Android requires while the link is up and nothing is playing. Never on the lock screen. */
    fun statusNotification(title: String, text: String): Notification =
        Notification.Builder(context, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_romcon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun toService(action: String): PendingIntent = PendingIntent.getService(
        context,
        0,
        Intent(context, MediaLinkService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
