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

    /** True if the setting is on and at least one paired PC could say what it's playing. */
    fun wanted(store: Store): Boolean {
        if (!store.loadSettings().lockScreenMedia) return false
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
}
