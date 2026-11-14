package dev.romcon.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A PC this phone has paired with. [token] is the secret the PC issued; [fingerprint] pins its certificate. */
data class SavedPc(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val lastUsed: Long,
)

/** App colours: follow the phone, or force light, dark, or pure-black dark for OLED screens. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

/**
 * A button that can sit on the bar under the trackpad. The names are saved in preferences, so don't rename them.
 * [label] is the short text under the icon; [title] and [summary] describe it in Settings.
 */
enum class BarButton(val label: String, val title: String, val summary: String) {
    KEYBOARD("Keyboard", "Keyboard", "Show or hide the phone keyboard to type on the PC"),
    WINDOWS("Windows", "Windows key", "Open the Start menu"),
    SHORTCUTS("Shortcuts", "Shortcuts", "Copy, paste, switch app and other shortcuts"),
    KEYS("Keys", "Keys", "Esc, Tab, arrows, F1–F12 and media keys"),
    SEND_TEXT("Send text", "Send text", "Write on the phone, then send it in one go"),
    SWITCH_APP("Switch app", "Switch app", "Alt+Tab; tap again to move through the windows"),
    TASK_VIEW("Task view", "Task view", "Win+Tab: every open window"),
    DESKTOP("Desktop", "Show desktop", "Win+D"),
    ESC("Esc", "Esc", "The Escape key"),
    ENTER("Enter", "Enter", "The Enter key"),
    COPY("Copy", "Copy", "Ctrl+C"),
    PASTE("Paste", "Paste", "Ctrl+V"),
    PLAY_PAUSE("Play", "Play / pause", "The media play/pause key"),
    VOLUME_DOWN("Vol −", "Volume down", "Hold to keep turning it down"),
    VOLUME_UP("Vol +", "Volume up", "Hold to keep turning it up"),
    MUTE("Mute", "Mute", "Mute or unmute the PC"),
    LEFT_CLICK("Left", "Left click", "Hold it and move on the trackpad to drag"),
    RIGHT_CLICK("Right", "Right click", "The right mouse button"),
    MIDDLE_CLICK("Middle", "Middle click", "The middle mouse button");

    companion object {
        const val MAX_ON_BAR = 5
        val DEFAULT = listOf(KEYBOARD, WINDOWS, SHORTCUTS)

        /** Reads a saved bar, dropping names this version doesn't know. An unusable value gives the default bar. */
        fun parse(saved: String?): List<BarButton> {
            if (saved == null) return DEFAULT
            val buttons = saved.split(',').mapNotNull { name -> entries.firstOrNull { it.name == name } }
            return buttons.distinct().take(MAX_ON_BAR).ifEmpty { DEFAULT }
        }

        fun format(buttons: List<BarButton>): String = buttons.joinToString(",") { it.name }
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val bottomBar: List<BarButton> = BarButton.DEFAULT,
    val pointerSpeed: Float = 1.0f,
    val acceleration: Float = 0.6f,
    val scrollSpeed: Float = 1.0f,
    val naturalScroll: Boolean = true,
    val smoothScroll: Boolean = true,
    val scrollMomentum: Boolean = true,
    val scrollBar: Boolean = true,
    val scrollBarReversed: Boolean = false,
    val tapToClick: Boolean = true,
    val typingPreview: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
)

/** Everything lives in app-private storage, and backups are disabled in the manifest so tokens never leave the phone. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("romcon", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString("device_id", id) }
        }

    val deviceName: String
        get() {
            val name = try {
                Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)
            } catch (_: Exception) {
                null
            }
            if (!name.isNullOrBlank()) return name
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            return if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        }

    fun loadPcs(): List<SavedPc> {
        val raw = prefs.getString("pcs", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedPc(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    fingerprint = o.getString("fingerprint"),
                    token = o.getString("token"),
                    lastUsed = o.optLong("lastUsed"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePcs(pcs: List<SavedPc>) {
        val array = JSONArray()
        pcs.forEach { pc ->
            array.put(
                JSONObject()
                    .put("id", pc.id)
                    .put("name", pc.name)
                    .put("host", pc.host)
                    .put("port", pc.port)
                    .put("fingerprint", pc.fingerprint)
                    .put("token", pc.token)
                    .put("lastUsed", pc.lastUsed),
            )
        }
        prefs.edit { putString("pcs", array.toString()) }
    }

    fun loadSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = prefs.getString("theme", null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: d.theme,
            bottomBar = BarButton.parse(prefs.getString("bottomBar", null)),
            pointerSpeed = prefs.getFloat("pointerSpeed", d.pointerSpeed),
            acceleration = prefs.getFloat("acceleration", d.acceleration),
            scrollSpeed = prefs.getFloat("scrollSpeed", d.scrollSpeed),
            naturalScroll = prefs.getBoolean("naturalScroll", d.naturalScroll),
            smoothScroll = prefs.getBoolean("smoothScroll", d.smoothScroll),
            scrollMomentum = prefs.getBoolean("scrollMomentum", d.scrollMomentum),
            scrollBar = prefs.getBoolean("scrollBar", d.scrollBar),
            scrollBarReversed = prefs.getBoolean("scrollBarReversed", d.scrollBarReversed),
            tapToClick = prefs.getBoolean("tapToClick", d.tapToClick),
            typingPreview = prefs.getBoolean("typingPreview", d.typingPreview),
            haptics = prefs.getBoolean("haptics", d.haptics),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
        )
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit {
            putString("theme", s.theme.name)
            putString("bottomBar", BarButton.format(s.bottomBar))
            putFloat("pointerSpeed", s.pointerSpeed)
            putFloat("acceleration", s.acceleration)
            putFloat("scrollSpeed", s.scrollSpeed)
            putBoolean("naturalScroll", s.naturalScroll)
            putBoolean("smoothScroll", s.smoothScroll)
            putBoolean("scrollMomentum", s.scrollMomentum)
            putBoolean("scrollBar", s.scrollBar)
            putBoolean("scrollBarReversed", s.scrollBarReversed)
            putBoolean("tapToClick", s.tapToClick)
            putBoolean("typingPreview", s.typingPreview)
            putBoolean("haptics", s.haptics)
            putBoolean("keepScreenOn", s.keepScreenOn)
        }
    }
}
