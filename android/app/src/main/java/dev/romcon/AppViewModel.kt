package dev.romcon

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.romcon.data.AppSettings
import dev.romcon.data.SavedPc
import dev.romcon.data.Store
import dev.romcon.input.Latch
import dev.romcon.input.ModKey
import dev.romcon.input.Vk
import dev.romcon.input.letterOrDigitVk
import dev.romcon.net.DiscoveredPc
import dev.romcon.net.Discovery
import dev.romcon.net.FailKind
import dev.romcon.net.Proto
import dev.romcon.net.RemoteException
import dev.romcon.net.RemoteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

enum class Screen { HOME, CONTROL, SETTINGS }

/** A page inside Settings; the settings list itself is shown when none is open. */
enum class SettingsPage { APPEARANCE, MOUSE, KEYBOARD, GENERAL }

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val pc: SavedPc, val attempt: Int, val lastError: String?) : ConnState
    data class Connected(val pc: SavedPc, val pcPaused: Boolean) : ConnState

    /** Needs the user: the PC forgot this phone, its identity changed, or versions don't match. */
    data class Failed(val pc: SavedPc, val kind: FailKind, val message: String) : ConnState
}

data class PairingUi(
    val name: String,
    val host: String,
    val port: Int,
    /** Set once the phone has seen the PC's certificate; the PIN is only sent to that same certificate. */
    val fingerprint: String? = null,
    val busy: Boolean = true,
    val error: String? = null,
    /** When re-pairing a saved PC, the entry to replace. */
    val replaceId: String? = null,
)

data class FoundPc(val pc: DiscoveredPc, val seenAt: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val deviceId = store.deviceId
    val deviceName = store.deviceName

    var screen by mutableStateOf(Screen.HOME)
        private set
    private var screenBeforeSettings = Screen.HOME

    var settingsPage by mutableStateOf<SettingsPage?>(null)
        private set

    private val _pcs = MutableStateFlow(store.loadPcs().sortedByDescending { it.lastUsed })
    val pcs: StateFlow<List<SavedPc>> = _pcs.asStateFlow()

    private val _found = MutableStateFlow<List<FoundPc>>(emptyList())
    val found: StateFlow<List<FoundPc>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _pairing = MutableStateFlow<PairingUi?>(null)
    val pairing: StateFlow<PairingUi?> = _pairing.asStateFlow()

    /** On-screen modifier latches (Ctrl, Alt, Shift, Win). */
    val latches = mutableStateMapOf<ModKey, Latch>()

    @Volatile private var session: RemoteSession? = null
    private var connectJob: Job? = null
    private var pairJob: Job? = null
    private var altTabJob: Job? = null
    private var altHeld = false
    private var resumePc: SavedPc? = null

    // ---------- Navigation ----------

    fun openSettings() {
        if (screen != Screen.SETTINGS) screenBeforeSettings = screen
        settingsPage = null
        screen = Screen.SETTINGS
    }

    fun openSettingsPage(page: SettingsPage) {
        settingsPage = page
    }

    fun back() {
        when (screen) {
            Screen.SETTINGS -> if (settingsPage != null) settingsPage = null else screen = screenBeforeSettings
            Screen.CONTROL -> disconnect()
            Screen.HOME -> Unit
        }
    }

    // ---------- Discovery ----------

    /** Broadcasts for PCs every few seconds while the home screen is visible. */
    suspend fun discoverWhileVisible() {
        while (true) {
            _scanning.value = true
            val results = withContext(Dispatchers.IO) { runCatching { Discovery.scan() }.getOrDefault(emptyList()) }
            _scanning.value = false
            val now = System.currentTimeMillis()
            _found.update { old ->
                val merged = old.associateBy { it.pc.fingerprint }.toMutableMap()
                results.forEach { merged[it.fingerprint] = FoundPc(it, now) }
                merged.values.filter { now - it.seenAt < 12_000 }.sortedBy { it.pc.name.lowercase() }
            }
            followMovedPcs(results)
            delay(3000)
        }
    }

    /** A paired PC that got a new LAN address from the router keeps working without re-adding it. */
    private fun followMovedPcs(results: List<DiscoveredPc>) {
        val moved = _pcs.value.mapNotNull { pc ->
            val seen = results.firstOrNull { it.fingerprint == pc.fingerprint } ?: return@mapNotNull null
            if ((seen.host != pc.host || seen.port != pc.port) && isPrivateLanIp(pc.host)) pc.copy(host = seen.host, port = seen.port) else null
        }
        moved.forEach(::upsert)
    }

    /** The PC's address from a recent broadcast reply, which beats a saved address that may be out of date. */
    private fun lanAddressFor(pc: SavedPc): Pair<String, Int>? {
        val fresh = System.currentTimeMillis() - 30_000
        return _found.value.firstOrNull { it.pc.fingerprint == pc.fingerprint && it.seenAt > fresh }?.pc?.let { it.host to it.port }
    }

    // ---------- Saved PCs ----------

    private fun upsert(pc: SavedPc) {
        _pcs.update { list -> (list.filterNot { it.id == pc.id } + pc).sortedByDescending { it.lastUsed } }
        store.savePcs(_pcs.value)
    }

    fun forget(pc: SavedPc) {
        _pcs.update { list -> list.filterNot { it.id == pc.id } }
        store.savePcs(_pcs.value)
    }

    fun editAddress(pc: SavedPc, host: String, port: Int) = upsert(pc.copy(host = host.trim(), port = port))

    // ---------- Pairing ----------

    fun pairDiscovered(found: DiscoveredPc) {
        val saved = _pcs.value.firstOrNull { it.fingerprint == found.fingerprint }
        if (saved != null) connect(saved) else startPairing(found.name, found.host, found.port, found.fingerprint, null)
    }

    fun addByAddress(host: String, port: Int) = startPairing(host.trim(), host.trim(), port, null, null)

    fun repair(pc: SavedPc, trustNewIdentity: Boolean) {
        connectJob?.cancel()
        closeSession()
        _conn.value = ConnState.Idle
        screen = Screen.HOME
        val (host, port) = lanAddressFor(pc) ?: (pc.host to pc.port)
        startPairing(pc.name, host, port, if (trustNewIdentity) null else pc.fingerprint, pc.id)
    }

    private fun startPairing(name: String, host: String, port: Int, knownFingerprint: String?, replaceId: String?) {
        pairJob?.cancel()
        _pairing.value = PairingUi(name, host, port, replaceId = replaceId)
        pairJob = viewModelScope.launch {
            try {
                // Look at the PC's certificate first so the user can compare codes before typing the PIN.
                val fingerprint = withContext(Dispatchers.IO) { RemoteSession.open(host, port, knownFingerprint).use { it.fingerprint } }
                _pairing.update { it?.copy(fingerprint = fingerprint, busy = false, error = null) }
            } catch (e: RemoteException) {
                _pairing.update { it?.copy(busy = false, error = e.message) }
            }
        }
    }

    fun retryPairing() {
        val p = _pairing.value ?: return
        startPairing(p.name, p.host, p.port, p.fingerprint, p.replaceId)
    }

    fun submitPin(pin: String) {
        val p = _pairing.value ?: return
        val fingerprint = p.fingerprint ?: return
        if (p.busy) return
        _pairing.value = p.copy(busy = true, error = null)
        pairJob = viewModelScope.launch {
            try {
                val (s, welcome) = withContext(Dispatchers.IO) {
                    val opened = RemoteSession.open(p.host, p.port, fingerprint)
                    opened to opened.hello(deviceId, deviceName, Proto.AUTH_PIN, pin.trim())
                }
                val existing = _pcs.value.firstOrNull { it.id == p.replaceId } ?: _pcs.value.firstOrNull { it.fingerprint == fingerprint }
                // Keep a Tailscale or DNS address the user typed; LAN addresses are picked up by discovery anyway.
                val host = existing?.host?.takeIf { !isPrivateLanIp(it) && p.replaceId != null } ?: p.host
                val pc = SavedPc(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = welcome.serverName.ifBlank { p.name },
                    host = host,
                    port = p.port,
                    fingerprint = fingerprint,
                    token = welcome.token,
                    lastUsed = System.currentTimeMillis(),
                )
                _pcs.update { list -> list.filterNot { it.id != pc.id && it.fingerprint == pc.fingerprint } }
                upsert(pc)
                _pairing.value = null

                connectJob?.cancel()
                closeSession()
                latches.clear()
                screen = Screen.CONTROL
                connectJob = viewModelScope.launch { connectionLoop(pc, s) }
            } catch (e: RemoteException) {
                _pairing.update { it?.copy(busy = false, error = pairingMessage(e)) }
            }
        }
    }

    fun cancelPairing() {
        pairJob?.cancel()
        _pairing.value = null
    }

    private fun pairingMessage(e: RemoteException): String = when (e.kind) {
        FailKind.WRONG_PIN -> "Wrong PIN. Check the PIN on the PC and try again."
        FailKind.PAIRING_CLOSED -> "Pairing isn't open on the PC. Click \"Pair a new phone\" in RomCon on the PC, then enter the new PIN."
        FailKind.TOO_MANY_ATTEMPTS -> "Too many wrong PINs. Click \"Pair a new phone\" on the PC to get a new PIN."
        else -> e.message ?: "Pairing failed."
    }

    // ---------- Connection ----------

    fun connect(pc: SavedPc) {
        connectJob?.cancel()
        closeSession()
        latches.clear()
        screen = Screen.CONTROL
        connectJob = viewModelScope.launch { connectionLoop(pc, null) }
    }

    /** Keeps the phone connected while the control screen is up, reconnecting after drops. */
    private suspend fun connectionLoop(start: SavedPc, alreadyOpen: RemoteSession?) {
        var pc = start
        var attempt = 0
        var lastError: String? = null
        var ready = alreadyOpen
        while (true) {
            val s: RemoteSession
            if (ready != null) {
                s = ready
                ready = null
            } else {
                _conn.value = ConnState.Connecting(pc, attempt, lastError)
                val (host, port) = lanAddressFor(pc) ?: (pc.host to pc.port)
                try {
                    s = withContext(Dispatchers.IO) {
                        val opened = RemoteSession.open(host, port, pc.fingerprint)
                        val welcome = opened.hello(deviceId, deviceName, Proto.AUTH_TOKEN, pc.token)
                        pc = pc.copy(name = welcome.serverName.ifBlank { pc.name }, lastUsed = System.currentTimeMillis())
                        opened
                    }
                } catch (e: RemoteException) {
                    if (e.kind in setOf(FailKind.NOT_PAIRED, FailKind.IDENTITY_CHANGED, FailKind.VERSION)) {
                        _conn.value = ConnState.Failed(pc, e.kind, e.message ?: "Can't connect.")
                        return
                    }
                    lastError = e.message
                    attempt++
                    delay(minOf(800L * attempt, 5000L))
                    continue
                }
                upsert(pc)
            }

            session = s
            attempt = 0
            lastError = null
            _conn.value = ConnState.Connected(pc, pcPaused = false)
            val error = awaitClose(s)
            if (session === s) session = null
            altHeld = false
            lastError = error.message
            attempt = 1
            delay(300)
        }
    }

    private suspend fun awaitClose(s: RemoteSession): RemoteException = suspendCancellableCoroutine { cont ->
        s.start(object : RemoteSession.Listener {
            override fun onStatus(pcPaused: Boolean) {
                _conn.update { if (it is ConnState.Connected) it.copy(pcPaused = pcPaused) else it }
            }

            override fun onClosed(error: RemoteException) {
                if (cont.isActive) cont.resume(error)
            }
        })
        cont.invokeOnCancellation { s.close() }
    }

    fun retryNow() {
        val pc = when (val c = _conn.value) {
            is ConnState.Connecting -> c.pc
            is ConnState.Failed -> c.pc
            is ConnState.Connected -> return
            ConnState.Idle -> return
        }
        connect(pc)
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        closeSession()
        resumePc = null
        _conn.value = ConnState.Idle
        screen = Screen.HOME
    }

    private fun closeSession() {
        altTabJob?.cancel()
        altHeld = false
        session?.close()
        session = null
    }

    /** Drop the connection while the app is in the background; the PC releases anything still held. */
    fun onBackground() {
        if (screen == Screen.CONTROL || screen == Screen.SETTINGS) {
            val pc = when (val c = _conn.value) {
                is ConnState.Connected -> c.pc
                is ConnState.Connecting -> c.pc
                else -> null
            }
            if (pc != null) {
                resumePc = pc
                connectJob?.cancel()
                closeSession()
            }
        }
    }

    fun onForeground() {
        val pc = resumePc ?: return
        resumePc = null
        if (screen == Screen.CONTROL || screen == Screen.SETTINGS) {
            connectJob?.cancel()
            connectJob = viewModelScope.launch { connectionLoop(pc, null) }
        }
    }

    // ---------- Settings ----------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        store.saveSettings(_settings.value)
    }

    // ---------- Input ----------

    fun move(dx: Int, dy: Int) = session?.move(dx, dy)

    fun scroll(vertical: Int, horizontal: Int) = session?.scroll(vertical, horizontal)

    fun button(button: Int, down: Boolean) = session?.button(button, down)

    fun click(button: Int) {
        session?.button(button, true)
        session?.button(button, false)
    }

    fun lockPc() = session?.command(Proto.CMD_LOCK)

    /** Types text exactly as given, ignoring on-screen modifier latches. */
    fun sendText(text: String) = session?.text(text)

    /** Text from the phone keyboard. With a modifier latched, each character becomes a shortcut instead. */
    fun type(text: String) {
        val s = session ?: return
        val mods = takeMods()
        if (mods == 0) {
            s.text(text)
            return
        }
        for (c in text) {
            val vk = letterOrDigitVk(c)
            when {
                c == '\n' -> s.key(Vk.ENTER, Proto.KEY_TAP, mods)
                vk != null -> s.key(vk, Proto.KEY_TAP, mods)
                else -> s.charWithMods(c, mods)
            }
        }
    }

    /** A key tap with the latched modifiers plus [extraMods]. */
    fun key(vk: Int, extraMods: Int = 0) {
        session?.key(vk, Proto.KEY_TAP, takeMods() or extraMods)
    }

    /** A fixed shortcut such as Ctrl+C; ignores and clears one-shot latches. */
    fun shortcut(vk: Int, mods: Int) {
        takeMods()
        session?.key(vk, Proto.KEY_TAP, mods)
    }

    /** Alt+Tab that keeps Alt held between taps, so tapping again moves through the window list. */
    fun switchApp() {
        val s = session ?: return
        if (!altHeld) {
            s.key(Vk.ALT, Proto.KEY_DOWN)
            altHeld = true
        }
        s.key(Vk.TAB, Proto.KEY_TAP)
        altTabJob?.cancel()
        altTabJob = viewModelScope.launch {
            delay(1100)
            session?.key(Vk.ALT, Proto.KEY_UP)
            altHeld = false
        }
    }

    fun toggleLatch(mod: ModKey) {
        latches[mod] = if ((latches[mod] ?: Latch.OFF) == Latch.OFF) Latch.ONCE else Latch.OFF
    }

    fun lockLatch(mod: ModKey) {
        latches[mod] = if (latches[mod] == Latch.LOCKED) Latch.OFF else Latch.LOCKED
    }

    private fun takeMods(): Int {
        var mods = 0
        for ((mod, latch) in latches.toMap()) {
            if (latch == Latch.OFF) continue
            mods = mods or mod.bit
            if (latch == Latch.ONCE) latches[mod] = Latch.OFF
        }
        return mods
    }

    override fun onCleared() {
        closeSession()
    }

    private fun isPrivateLanIp(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
    }
}
