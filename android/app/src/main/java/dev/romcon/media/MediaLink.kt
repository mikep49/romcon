package dev.romcon.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.romcon.data.Store
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the background link to the PC stands, for the Settings page. */
sealed interface LinkStatus {
    /** Not running: the setting is off, or there's no PC it could watch. */
    data object Off : LinkStatus

    data object Searching : LinkStatus

    /** [showing] is true while the PC has something playing and the controls are up. */
    data class Connected(val pcName: String, val showing: Boolean) : LinkStatus
}

/**
 * The lock screen media controls, seen from the rest of the app. The work happens in [MediaLinkService], which
 * stays connected to a paired PC in the background and shows a media notification while that PC plays something.
 */
object MediaLink {
    internal val status = MutableStateFlow<LinkStatus>(LinkStatus.Off)
    val state: StateFlow<LinkStatus> = status.asStateFlow()

    /** Tells the running service to look again: settings or PCs changed, the screen came on, the network changed. */
    internal val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Tells the running service to look at the time again: the app was opened or closed, or the phone woke up. */
    internal val clock = Channel<Unit>(Channel.CONFLATED)

    /** True while the app is on screen. Kept in memory only: when the process has just started, the app isn't open. */
    @Volatile private var open = false

    /** True if the setting is on, its time isn't up, and at least one paired PC could say what it's playing. */
    fun wanted(store: Store): Boolean {
        if (!store.loadSettings().lockScreenMedia || timeUp(store)) return false
        val without = store.pcsWithoutMedia()
        return store.loadPcs().any { it.fingerprint !in without }
    }

    /**
     * Starts or stops the service to match the settings and the paired PCs. Call it when either changes, when the
     * app comes to the front, and after the phone starts; those are the moments Android lets a service start.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        val service = Intent(app, MediaLinkService::class.java)
        if (wanted(Store(app))) {
            try {
                ContextCompat.startForegroundService(app, service)
            } catch (_: RuntimeException) {
                // Android refused a start from the background. The next time the app is opened will do.
            }
            kicks.trySend(Unit)
        } else {
            app.stopService(service)
        }
    }

    /** Call when the app comes to the front. While it's open, the link has no time limit, and it starts if it was stopped. */
    fun appOpened(context: Context) {
        open = true
        Store(context.applicationContext).closedAt = 0
        clock.trySend(Unit)
        sync(context)
    }

    /** Call when the app leaves the screen. From then on, the link stays for as long as Settings allow. */
    fun appClosed(context: Context) {
        open = false
        Store(context.applicationContext).closedAt = System.currentTimeMillis()
        clock.trySend(Unit)
    }

    /** When the link must stop, by the wall clock, or null if it may stay: the app is open, or Settings set no limit. */
    internal fun deadline(store: Store): Long? {
        if (open) return null
        val minutes = store.loadSettings().backgroundMinutes
        if (minutes == BackgroundLimit.NONE) return null
        val now = System.currentTimeMillis()
        var closedAt = store.closedAt
        // Not known if the phone restarted while the app was open, and later than now if the clock was set back.
        if (closedAt <= 0 || closedAt > now) {
            closedAt = now
            store.closedAt = now
        }
        return BackgroundLimit.deadline(minutes, closedAt)
    }

    /** True once the app has been closed for as long as Settings allow the link to stay. */
    internal fun timeUp(store: Store): Boolean = deadline(store)?.let { System.currentTimeMillis() >= it } ?: false
}
