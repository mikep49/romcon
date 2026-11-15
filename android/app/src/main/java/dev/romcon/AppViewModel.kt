package dev.romcon

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.romcon.data.AppSettings
import dev.romcon.data.BarItem
import dev.romcon.data.CustomShortcut
import dev.romcon.data.KeyCombo
import dev.romcon.data.SavedPc
import dev.romcon.data.ShortcutImages
import dev.romcon.data.Store
import dev.romcon.data.Transport
import dev.romcon.input.Latch
import dev.romcon.input.ModKey
import dev.romcon.input.TypedLine
import dev.romcon.input.Vk
import dev.romcon.input.letterOrDigitVk
import dev.romcon.media.MediaLink
import dev.romcon.net.DiscoveredPc
import dev.romcon.net.Discovery
import dev.romcon.net.FailKind
import dev.romcon.net.Proto
import dev.romcon.net.RemoteException
import dev.romcon.net.RemoteSession
import dev.romcon.net.isPrivateLanIp
import dev.romcon.update.UpdateStage
import dev.romcon.update.UpdateStatus
import dev.romcon.update.Updates
import dev.romcon.update.isNewer
import kotlinx.coroutines.CancellationException
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
enum class SettingsPage { APPEARANCE, MOUSE, KEYBOARD, BOTTOM_BAR, SHORTCUT, MEDIA, CONNECTION, GENERAL }

/** A custom shortcut being made or changed in Settings; [original] is the saved one, or null for a new one. */
data class ShortcutDraft(val shortcut: CustomShortcut, val original: CustomShortcut?) {
    val changed: Boolean get() = shortcut != original
}

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val pc: SavedPc, val attempt: Int, val lastError: String?) : ConnState
    /** [udp] is true while pointer movement is going over UDP; it starts on TCP and switches once the PC confirms. */
    data class Connected(val pc: SavedPc, val pcPaused: Boolean, val udp: Boolean = false) : ConnState

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

    /** What the shortcut editor shows while [settingsPage] is [SettingsPage.SHORTCUT]. */
    var shortcutDraft by mutableStateOf<ShortcutDraft?>(null)
        private set

    /** Back was pressed in the shortcut editor with unsaved changes. */
    var confirmDiscard by mutableStateOf(false)
        private set

    /** Why the picture just chosen for a shortcut couldn't be used. */
    var shortcutImageError by mutableStateOf<String?>(null)
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

    /** What the phone has typed on the PC, for the preview above the keyboard. */
    var typed by mutableStateOf(TypedLine.EMPTY)
        private set

    /** This app's version, as in the release tags. */
    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    private val _update = MutableStateFlow(UpdateStatus())
    val update: StateFlow<UpdateStatus> = _update.asStateFlow()

    /** The user put off the update offered on the home screen; Settings still offers it. */
    var updateBannerDismissed by mutableStateOf(false)
        private set

    @Volatile private var session: RemoteSession? = null
    private var connectJob: Job? = null
    private var pairJob: Job? = null
    private var altTabJob: Job? = null
    private var altHeld = false
    private var resumePc: SavedPc? = null

    init {
        // A finished update leaves its APK behind, and the new version is running by now.
        viewModelScope.launch(Dispatchers.IO) { Updates.apkFile(app).delete() }
        deleteUnusedShortcutImages()
        checkForUpdates()
    }

    // ---------- Navigation ----------

    fun openSettings() {
        if (screen != Screen.SETTINGS) screenBeforeSettings = screen
        settingsPage = null
        shortcutDraft = null
        confirmDiscard = false
        screen = Screen.SETTINGS
    }

    fun openSettingsPage(page: SettingsPage) {
        settingsPage = page
    }

    fun back() {
        when (screen) {
            Screen.SETTINGS -> when {
                settingsPage == SettingsPage.SHORTCUT -> if (shortcutDraft?.changed == true) confirmDiscard = true else closeShortcutEditor()
                settingsPage != null -> settingsPage = null
                else -> screen = screenBeforeSettings
            }
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
        MediaLink.sync(getApplication())
    }

    /** True for a paired PC whose RomCon is too old to offer lock screen media controls. */
    fun lacksMedia(pc: SavedPc): Boolean = pc.fingerprint in store.pcsWithoutMedia()

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
                store.setMediaSupport(fingerprint, welcome.media)
                MediaLink.sync(getApplication())
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
        var mediaChanged = false
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
                        mediaChanged = store.setMediaSupport(pc.fingerprint, welcome.media)
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
                // The PC was updated (or rolled back) since the phone last asked whether it can show its media.
                if (mediaChanged) MediaLink.sync(getApplication())
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
        s.preferUdp = _settings.value.transport == Transport.UDP
        s.start(object : RemoteSession.Listener {
            override fun onStatus(pcPaused: Boolean) {
                _conn.update { if (it is ConnState.Connected) it.copy(pcPaused = pcPaused) else it }
            }

            override fun onUdp(active: Boolean) {
                if (session === s) _conn.update { if (it is ConnState.Connected) it.copy(udp = active) else it }
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
        typed = TypedLine.EMPTY
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
        // Android lets the background media link start while the app is in front, so this is the moment to check on it.
        MediaLink.sync(getApplication())
        val pc = resumePc ?: return
        resumePc = null
        if (screen == Screen.CONTROL || screen == Screen.SETTINGS) {
            connectJob?.cancel()
            connectJob = viewModelScope.launch { connectionLoop(pc, null) }
        }
    }

    // ---------- Settings ----------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = _settings.value
        _settings.update(transform)
        store.saveSettings(_settings.value)
        if (before.lockScreenMedia != _settings.value.lockScreenMedia || before.volumeKeys != _settings.value.volumeKeys) {
            MediaLink.sync(getApplication())
        }
        if (!_settings.value.typingPreview) typed = TypedLine.EMPTY
        val udp = _settings.value.transport == Transport.UDP
        session?.let { if (it.preferUdp != udp) it.preferUdp = udp }
    }

    // ---------- Custom shortcuts ----------

    fun newShortcut() {
        openShortcutEditor(ShortcutDraft(CustomShortcut(CustomShortcut.newId(), name = "", keys = listOf(KeyCombo())), original = null))
    }

    fun editShortcut(shortcut: CustomShortcut) = openShortcutEditor(ShortcutDraft(shortcut, shortcut))

    private fun openShortcutEditor(draft: ShortcutDraft) {
        shortcutDraft = draft
        confirmDiscard = false
        shortcutImageError = null
        settingsPage = SettingsPage.SHORTCUT
    }

    fun editDraft(transform: (CustomShortcut) -> CustomShortcut) {
        shortcutDraft = shortcutDraft?.let { it.copy(shortcut = transform(it.shortcut)) }
    }

    /** Saves the shortcut being edited. A new one also goes on the bar when there's room. */
    fun saveDraft() {
        val draft = shortcutDraft ?: return
        if (!draft.shortcut.ready) return
        val saved = draft.shortcut.copy(name = draft.shortcut.name.trim())
        updateSettings { s ->
            if (s.shortcut(saved.id) != null) {
                s.copy(shortcuts = s.shortcuts.map { if (it.id == saved.id) saved else it })
            } else {
                val bar = if (s.bottomBar.size < BarItem.MAX) s.bottomBar + BarItem.Custom(saved.id) else s.bottomBar
                s.copy(shortcuts = s.shortcuts + saved, bottomBar = bar)
            }
        }
        closeShortcutEditor()
    }

    fun deleteDraft() {
        val id = shortcutDraft?.shortcut?.id ?: return
        updateSettings { s ->
            // The bar always keeps a button, so taking off its last one brings back the default.
            val bar = s.bottomBar.filterNot { it == BarItem.Custom(id) }.ifEmpty { BarItem.DEFAULT }
            s.copy(shortcuts = s.shortcuts.filterNot { it.id == id }, bottomBar = bar)
        }
        closeShortcutEditor()
    }

    fun discardDraft() = closeShortcutEditor()

    fun keepEditing() {
        confirmDiscard = false
    }

    private fun closeShortcutEditor() {
        shortcutDraft = null
        confirmDiscard = false
        shortcutImageError = null
        settingsPage = SettingsPage.BOTTOM_BAR
        deleteUnusedShortcutImages()
    }

    /** Copies a picture from the phone in as the icon of the shortcut being edited. */
    fun importShortcutImage(uri: Uri) {
        val id = shortcutDraft?.shortcut?.id ?: return
        shortcutImageError = null
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { ShortcutImages.import(getApplication(), uri, id) }
                if (shortcutDraft?.shortcut?.id == id) editDraft { it.copy(image = name) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                shortcutImageError = "Couldn't use that picture. Try another one."
            }
        }
    }

    private fun deleteUnusedShortcutImages() {
        val keep = _settings.value.shortcuts.mapNotNull { it.image }.toSet() + setOfNotNull(shortcutDraft?.shortcut?.image)
        viewModelScope.launch(Dispatchers.IO) { ShortcutImages.deleteAllExcept(getApplication(), keep) }
    }

    // ---------- Updates ----------

    /** Asks GitHub whether there's a newer RomCon. Runs when the app opens, and from Settings. */
    fun checkForUpdates() {
        val before = _update.value
        if (before.busy) return
        _update.value = before.copy(stage = UpdateStage.CHECKING, error = null)
        viewModelScope.launch {
            _update.value = try {
                val release = withContext(Dispatchers.IO) { Updates.fetchLatest(appVersion) }
                val newer = isNewer(release.version, appVersion)
                UpdateStatus(if (newer) UpdateStage.AVAILABLE else UpdateStage.UP_TO_DATE, release, newer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                before.copy(stage = UpdateStage.FAILED, error = "Couldn't check for updates. ${Updates.describe(e)}")
            }
        }
    }

    /** Downloads the new APK and opens Android's installer on it. */
    fun installUpdate() {
        val before = _update.value
        val release = before.latest ?: return
        if (!before.hasUpdate || before.busy) return
        _update.value = before.copy(stage = UpdateStage.DOWNLOADING, progress = null, error = null)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val apk = Updates.apkFile(app)
            try {
                withContext(Dispatchers.IO) {
                    Updates.download(release, appVersion, apk) { p -> _update.update { it.copy(progress = p) } }
                }
                _update.value = before.copy(stage = UpdateStage.AVAILABLE, error = null)
                app.startActivity(Updates.installIntent(app, apk))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = before.copy(stage = UpdateStage.FAILED, error = "Couldn't update. ${Updates.describe(e)}")
            }
        }
    }

    fun dismissUpdateBanner() {
        updateBannerDismissed = true
    }

    // ---------- Input ----------

    fun move(dx: Int, dy: Int) = session?.move(dx, dy)

    fun scroll(vertical: Int, horizontal: Int) = session?.scroll(vertical, horizontal)

    fun button(button: Int, down: Boolean) {
        val s = session ?: return
        s.button(button, down)
        if (down) mirror { TypedLine.EMPTY } // a click usually moves the caret somewhere else
    }

    fun click(button: Int) {
        button(button, true)
        button(button, false)
    }

    fun lockPc() {
        val s = session ?: return
        s.command(Proto.CMD_LOCK)
        mirror { TypedLine.EMPTY }
    }

    /** Types text exactly as given, ignoring on-screen modifier latches. */
    fun sendText(text: String) {
        val s = session ?: return
        s.text(text)
        mirror { it.insert(text) }
    }

    /** Text from the phone keyboard. With a modifier latched, each character becomes a shortcut instead. */
    fun type(text: String) {
        val s = session ?: return
        val mods = takeMods()
        if (mods == 0) {
            s.text(text)
            mirror { it.insert(text) }
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
        mirror { TypedLine.EMPTY }
    }

    /** A key tap with the latched modifiers plus [extraMods]. */
    fun key(vk: Int, extraMods: Int = 0) {
        val s = session ?: return
        val mods = takeMods() or extraMods
        s.key(vk, Proto.KEY_TAP, mods)
        mirror { it.key(vk, mods) }
    }

    /** A fixed shortcut such as Ctrl+C; ignores and clears one-shot latches. */
    fun shortcut(vk: Int, mods: Int) {
        takeMods()
        val s = session ?: return
        s.key(vk, Proto.KEY_TAP, mods)
        mirror { it.key(vk, mods) }
    }

    /** Presses a custom shortcut's hotkeys one after the other. Like [shortcut], it ignores and clears one-shot latches. */
    fun runShortcut(shortcut: CustomShortcut) {
        takeMods()
        val s = session ?: return
        shortcut.keys.forEach { s.key(it.vk, Proto.KEY_TAP, it.mods) }
        mirror { line -> shortcut.keys.fold(line) { typed, combo -> typed.key(combo.vk, combo.mods) } }
    }

    /** Alt+Tab that keeps Alt held between taps, so tapping again moves through the window list. */
    fun switchApp() {
        val s = session ?: return
        if (!altHeld) {
            s.key(Vk.ALT, Proto.KEY_DOWN)
            altHeld = true
        }
        s.key(Vk.TAB, Proto.KEY_TAP)
        mirror { TypedLine.EMPTY }
        altTabJob?.cancel()
        altTabJob = viewModelScope.launch {
            delay(1100)
            session?.key(Vk.ALT, Proto.KEY_UP)
            altHeld = false
        }
    }

    fun clearTyped() {
        typed = TypedLine.EMPTY
    }

    /** Keeps [typed] in step with what was just sent. A paused PC ignores input, so nothing is typed there. */
    private fun mirror(change: (TypedLine) -> TypedLine) {
        if (!_settings.value.typingPreview) return
        if ((_conn.value as? ConnState.Connected)?.pcPaused != false) return
        typed = change(typed)
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
}
