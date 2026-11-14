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

data class AppSettings(
    val pointerSpeed: Float = 1.0f,
    val acceleration: Float = 0.6f,
    val scrollSpeed: Float = 1.0f,
    val naturalScroll: Boolean = true,
    val smoothScroll: Boolean = true,
    val scrollMomentum: Boolean = true,
    val tapToClick: Boolean = true,
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
            pointerSpeed = prefs.getFloat("pointerSpeed", d.pointerSpeed),
            acceleration = prefs.getFloat("acceleration", d.acceleration),
            scrollSpeed = prefs.getFloat("scrollSpeed", d.scrollSpeed),
            naturalScroll = prefs.getBoolean("naturalScroll", d.naturalScroll),
            smoothScroll = prefs.getBoolean("smoothScroll", d.smoothScroll),
            scrollMomentum = prefs.getBoolean("scrollMomentum", d.scrollMomentum),
            tapToClick = prefs.getBoolean("tapToClick", d.tapToClick),
            haptics = prefs.getBoolean("haptics", d.haptics),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
        )
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit {
            putFloat("pointerSpeed", s.pointerSpeed)
            putFloat("acceleration", s.acceleration)
            putFloat("scrollSpeed", s.scrollSpeed)
            putBoolean("naturalScroll", s.naturalScroll)
            putBoolean("smoothScroll", s.smoothScroll)
            putBoolean("scrollMomentum", s.scrollMomentum)
            putBoolean("tapToClick", s.tapToClick)
            putBoolean("haptics", s.haptics)
            putBoolean("keepScreenOn", s.keepScreenOn)
        }
    }
}
