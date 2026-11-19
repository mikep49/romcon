package dev.romcon.media

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.romcon.data.SavedPc
import dev.romcon.data.Store
import dev.romcon.net.DiscoveredPc
import dev.romcon.net.Discovery
import dev.romcon.net.FailKind
import dev.romcon.net.MediaState
import dev.romcon.net.Proto
import dev.romcon.net.RemoteException
import dev.romcon.net.RemoteSession
import dev.romcon.net.isPrivateLanIp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps a media link to a paired PC while the app isn't open, and shows media controls on the lock screen
 * whenever that PC is playing something. The link carries no pointer or keyboard input, and the PC accepts none on it.
 *
 * Android only lets an app stay connected in the background as a foreground service, which must show a
 * notification. While the PC is playing, that is the media notification. The rest of the time it's a silent one
 * that is hidden from the lock screen and that the user can switch off.
 *
 * While the phone sleeps this costs next to nothing: nothing is sent until the PC has news, and that wakes the phone.
 *
 * Settings can limit how long it stays once the app is closed. It stops then, and starts again when the app is opened.
 */
class MediaLinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var controls: PcMediaControls
    private lateinit var notifications: NotificationManager
    private var loop: Job? = null
    private var clock: Job? = null

    /** When the alarm that stops the service is set for, by the wall clock. */
    private var alarmAt: Long? = null

    // Main thread only.
    private var session: RemoteSession? = null
    private var linked: SavedPc? = null
    private var media = MediaState.NONE
    private var receivedAt = 0L
    private var volumeKeys = true

    /** The user swiped the controls away; they stay away until the PC starts playing again or moves to another track. */
    private var dismissed = false
    private var shownId = 0
    private var shownKey: Any? = null
    private var destroyed = false

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = wake()
    }

    private val networkChanged = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wake()

        override fun onLost(network: Network) = wake()
    }

    private val clockAlarm by lazy {
        PendingIntent.getService(this, 0, Intent(this, MediaLinkService::class.java).setAction(ACTION_CLOCK), PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        notifications = getSystemService(NotificationManager::class.java)
        PcMediaControls.createChannels(this)
        controls = PcMediaControls(this) { command, value -> session?.mediaCommand(command, value) }
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkChanged)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            null -> Unit
            ACTION_CLOCK -> MediaLink.clock.trySend(Unit)
            PcMediaControls.ACTION_DISMISSED -> {
                dismissed = true
                render()
            }
            else -> controls.handle(action)
        }
        // Whatever started the service, Android expects the notification promptly.
        if (shownId == 0 && !display(PcMediaControls.ID_STATUS, statusNotification(), "starting")) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) {
            loop = scope.launch {
                try {
                    run()
                } finally {
                    loop = null
                    stopSelf()
                }
            }
        }
        // Only once the notification is up: stopping before it would crash the app.
        if (clock == null) clock = scope.launch { keepTime() }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        wakeAt(null)
        scope.cancel()
        session?.close()
        session = null
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkChanged)
        unregisterReceiver(screenOn)
        controls.release()
        MediaLink.status.value = LinkStatus.Off
        super.onDestroy()
    }

    // ---------- How long ----------

    /** The screen came on or the network changed: check on the link, and on the time. */
    private fun wake() {
        MediaLink.clock.trySend(Unit)
        MediaLink.kicks.trySend(Unit)
    }

    /** Stops the service once the app has been closed for as long as Settings allow. */
    private suspend fun keepTime() {
        while (true) {
            val deadline = MediaLink.deadline(store)
            val left = deadline?.let { it - System.currentTimeMillis() }
            if (left != null && left <= 0) {
                stopSelf()
                return
            }
            wakeAt(deadline)
            // Delays stop while the phone sleeps, so one long wait could end long after the deadline. Short ones, and
            // a look each time the phone wakes up, keep to the clock.
            if (left == null) {
                MediaLink.clock.receive()
            } else {
                withTimeoutOrNull(left.coerceAtMost(CLOCK_MS)) { MediaLink.clock.receive() }
            }
        }
    }

    /** Has Android wake the phone at [at], by the wall clock, in case nothing else does by then; null cancels it. */
    private fun wakeAt(at: Long?) {
        if (at == alarmAt) return
        alarmAt = at
        val alarms = getSystemService(AlarmManager::class.java)
        // Inexact, but that's fine here, and an exact alarm needs a permission the user would have to grant.
        if (at == null) alarms.cancel(clockAlarm) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, clockAlarm)
    }

    // ---------- Staying connected ----------

    /** Connects to a paired PC and stays with it; looks again when the link drops. Returns when there's nothing left to do. */
    private suspend fun run() {
        // PCs that turned this phone down. Asking again is pointless until it's paired again, which changes the token.
        val refused = HashSet<String>()
        var failures = 0
        while (true) {
            MediaLink.kicks.tryReceive()
            if (!store.loadSettings().lockScreenMedia) return
            val without = store.pcsWithoutMedia()
            val pcs = store.loadPcs().filter { it.fingerprint !in without && it.token !in refused }.sortedByDescending { it.lastUsed }
            if (pcs.isEmpty()) return

            linked = null
            render()
            val found = withContext(Dispatchers.IO) {
                val link = connect(pcs, refused)
                // If the service was stopped meanwhile, nobody is left to use the connection.
                if (link != null && !isActive) {
                    link.second.close()
                    null
                } else {
                    link
                }
            }
            if (found == null) {
                failures++
                // A kick (screen on, new network, app opened) cuts the wait short.
                withTimeoutOrNull(RETRY_MS[minOf(failures, RETRY_MS.size) - 1]) { MediaLink.kicks.receive() }
                continue
            }
            failures = 0
            stayWith(found.first, found.second)
            delay(1000)
        }
    }

    /** Blocking. Tries each PC at its saved address, then wherever a broadcast finds it on this network. */
    private fun connect(pcs: List<SavedPc>, refused: MutableSet<String>): Pair<SavedPc, RemoteSession>? {
        val local = onLocalNetwork()
        var discovered: List<DiscoveredPc>? = null
        for (pc in pcs) {
            // A home address is no use on mobile data; a Tailscale or DNS address may be.
            if (isPrivateLanIp(pc.host) && !local) continue
            var address: Pair<String, Int>? = pc.host to pc.port
            var triedDiscovery = false
            while (address != null) {
                val (host, port) = address
                address = null
                try {
                    val s = RemoteSession.open(host, port, pc.fingerprint)
                    val welcome = s.hello(store.deviceId, store.deviceName, Proto.AUTH_TOKEN, pc.token, Proto.ROLE_MEDIA)
                    if (welcome.media) return pc.copy(name = welcome.serverName.ifBlank { pc.name }) to s
                    // An older RomCon on the PC, which took this for a control connection. Leave it alone from now on.
                    s.close()
                    store.setMediaSupport(pc.fingerprint, false)
                } catch (e: RemoteException) {
                    if (e.kind == FailKind.NOT_PAIRED || e.kind == FailKind.VERSION) {
                        refused += pc.token
                    } else if (local && !triedDiscovery) {
                        // Not there, or another machine has that address now. The router may have moved the PC.
                        triedDiscovery = true
                        val seen = discovered ?: runCatching { Discovery.scan() }.getOrDefault(emptyList()).also { discovered = it }
                        address = seen.firstOrNull { it.fingerprint == pc.fingerprint && (it.host != host || it.port != port) }?.let { it.host to it.port }
                    }
                }
            }
        }
        return null
    }

    private fun onLocalNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Shows what [pc] plays until the link drops or is no longer wanted. */
    private suspend fun stayWith(pc: SavedPc, s: RemoteSession) {
        val over = CompletableDeferred<Unit>()
        session = s
        linked = pc
        media = MediaState.NONE
        dismissed = false
        volumeKeys = store.loadSettings().volumeKeys
        render()
        s.start(
            object : RemoteSession.Listener {
                override fun onStatus(pcPaused: Boolean) = Unit

                override fun onMedia(media: MediaState) {
                    val at = SystemClock.elapsedRealtime()
                    main.post { if (session === s) onMedia(media, at) }
                }

                override fun onArt(artId: Int, jpeg: ByteArray) {
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
                    main.post { if (session === s && controls.setArt(artId, bitmap)) render() }
                }

                override fun onClosed(error: RemoteException) {
                    over.complete(Unit)
                }
            },
            relaxed = true,
        )
        try {
            coroutineScope {
                val checks = launch {
                    for (kick in MediaLink.kicks) {
                        val settings = store.loadSettings()
                        if (!settings.lockScreenMedia || store.loadPcs().none { it.fingerprint == pc.fingerprint && it.token == pc.token }) break
                        if (settings.volumeKeys != volumeKeys) {
                            volumeKeys = settings.volumeKeys
                            render()
                        }
                        // The phone may have slept through the PC going away. Ask, rather than show stale controls.
                        val asked = System.nanoTime()
                        s.ping()
                        delay(PROBE_MS)
                        if (s.lastHeard - asked < 0) break
                    }
                    over.complete(Unit)
                }
                over.await()
                checks.cancel()
            }
        } finally {
            s.close()
            session = null
            linked = null
            media = MediaState.NONE
            render()
        }
    }

    private fun onMedia(now: MediaState, at: Long) {
        val before = media
        media = now
        receivedAt = at
        if (!now.showing || (now.playing && !before.playing) || now.title != before.title) dismissed = false
        render()
    }

    // ---------- What the user sees ----------

    /** Brings the media session and the notification in line with the current state. */
    private fun render() {
        if (destroyed) return
        if (MediaLink.timeUp(store)) {
            // The phone slept through the deadline and woke up for news from the PC. Show nothing: the clock stops the service.
            MediaLink.clock.trySend(Unit)
            return
        }
        val pc = linked
        if (pc != null && media.showing && !dismissed) {
            controls.show(media, pc.name, receivedAt, volumeKeys)
            display(PcMediaControls.ID_MEDIA, controls.notification(), controls.notificationKey())
        } else {
            controls.hide()
            display(PcMediaControls.ID_STATUS, statusNotification(), pc?.name ?: "")
        }
        MediaLink.status.value = if (pc == null) LinkStatus.Searching else LinkStatus.Connected(pc.name, media.showing)
    }

    private fun statusNotification(): Notification {
        val pc = linked
        return if (pc == null) {
            controls.statusNotification("Looking for your PC", "Media controls appear here once it's found and playing something.")
        } else {
            controls.statusNotification("Connected to ${pc.name}", "Media controls appear here while it's playing something.")
        }
    }

    /**
     * Makes [notification] the service's one notification. Moving to another id takes the previous one away, so
     * the media controls and the quiet status never show together. False if Android won't let the service run.
     */
    private fun display(id: Int, notification: Notification, key: Any): Boolean {
        if (id == shownId) {
            if (key != shownKey) {
                shownKey = key
                notifications.notify(id, notification)
            }
            return true
        }
        return try {
            ServiceCompat.startForeground(
                this,
                id,
                notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
            shownId = id
            shownKey = key
            true
        } catch (_: RuntimeException) {
            // Started from the background at a moment Android doesn't allow. Opening the app starts it again.
            false
        }
    }

    private companion object {
        /** How long to wait before looking for the PC again, by the number of failures so far. */
        val RETRY_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000, 120_000, 300_000)
        const val PROBE_MS = 3_000L

        /** How often to look at the clock while the app is closed and the time is limited. */
        const val CLOCK_MS = 60_000L

        /** The alarm that stops the service when the phone slept through its deadline. */
        const val ACTION_CLOCK = "dev.romcon.media.CLOCK"
    }
}
