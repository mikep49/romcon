package dev.romcon.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the media link back after the phone restarts or the app is updated, without the app being opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> MediaLink.sync(context)
        }
    }
}
