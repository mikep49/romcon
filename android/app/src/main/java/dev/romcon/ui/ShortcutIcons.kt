package dev.romcon.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.romcon.data.CustomShortcut
import dev.romcon.data.ShortcutImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A built-in icon a custom shortcut can show. [key] is saved with the shortcut, so don't change it. */
class ShortcutSymbol(val key: String, val label: String, val vector: ImageVector)

val shortcutSymbols: List<ShortcutSymbol> = listOf(
    ShortcutSymbol(CustomShortcut.DEFAULT_ICON, "Command key", Icons.Filled.KeyboardCommandKey),
    ShortcutSymbol("search", "Search", Icons.Filled.Search),
    ShortcutSymbol("rocket", "Rocket", Icons.Filled.RocketLaunch),
    ShortcutSymbol("bolt", "Lightning bolt", Icons.Filled.Bolt),
    ShortcutSymbol("star", "Star", Icons.Filled.Star),
    ShortcutSymbol("apps", "Apps", Icons.Filled.Apps),
    ShortcutSymbol("launch", "Open", Icons.AutoMirrored.Filled.Launch),
    ShortcutSymbol("terminal", "Terminal", Icons.Filled.Terminal),
    ShortcutSymbol("code", "Code", Icons.Filled.Code),
    ShortcutSymbol("folder", "Folder", Icons.Filled.Folder),
    ShortcutSymbol("web", "Web", Icons.Filled.Public),
    ShortcutSymbol("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    ShortcutSymbol("mail", "Mail", Icons.Filled.Mail),
    ShortcutSymbol("mic", "Microphone", Icons.Filled.Mic),
    ShortcutSymbol("mic_off", "Microphone off", Icons.Filled.MicOff),
    ShortcutSymbol("camera", "Camera", Icons.Filled.Videocam),
    ShortcutSymbol("camera_off", "Camera off", Icons.Filled.VideocamOff),
    ShortcutSymbol("screenshot", "Screenshot", Icons.Filled.ScreenshotMonitor),
    ShortcutSymbol("music", "Music", Icons.Filled.MusicNote),
    ShortcutSymbol("headphones", "Headphones", Icons.Filled.Headphones),
    ShortcutSymbol("game", "Game", Icons.Filled.SportsEsports),
    ShortcutSymbol("movie", "Movie", Icons.Filled.Movie),
    ShortcutSymbol("brush", "Brush", Icons.Filled.Brush),
    ShortcutSymbol("palette", "Palette", Icons.Filled.Palette),
    ShortcutSymbol("edit", "Pencil", Icons.Filled.Edit),
    ShortcutSymbol("cut", "Scissors", Icons.Filled.ContentCut),
    ShortcutSymbol("translate", "Translate", Icons.Filled.Translate),
    ShortcutSymbol("calculate", "Calculator", Icons.Filled.Calculate),
    ShortcutSymbol("calendar", "Calendar", Icons.Filled.CalendarMonth),
    ShortcutSymbol("timer", "Timer", Icons.Filled.Timer),
    ShortcutSymbol("ai", "Sparkles", Icons.Filled.AutoAwesome),
    ShortcutSymbol("robot", "Robot", Icons.Filled.SmartToy),
    ShortcutSymbol("lightbulb", "Light bulb", Icons.Filled.Lightbulb),
    ShortcutSymbol("dark_mode", "Moon", Icons.Filled.DarkMode),
    ShortcutSymbol("settings", "Settings", Icons.Filled.Settings),
    ShortcutSymbol("lock", "Lock", Icons.Filled.Lock),
    ShortcutSymbol("power", "Power", Icons.Filled.PowerSettingsNew),
    ShortcutSymbol("refresh", "Refresh", Icons.Filled.Refresh),
    ShortcutSymbol("save", "Floppy disk", Icons.Filled.Save),
    ShortcutSymbol("fullscreen", "Full screen", Icons.Filled.Fullscreen),
    ShortcutSymbol("dashboard", "Dashboard", Icons.Filled.Dashboard),
    ShortcutSymbol("widgets", "Widgets", Icons.Filled.Widgets),
    ShortcutSymbol("extension", "Puzzle piece", Icons.Filled.Extension),
    ShortcutSymbol("cloud", "Cloud", Icons.Filled.Cloud),
    ShortcutSymbol("bookmark", "Bookmark", Icons.Filled.Bookmark),
    ShortcutSymbol("favorite", "Heart", Icons.Filled.Favorite),
    ShortcutSymbol("home", "Home", Icons.Filled.Home),
)

private val symbolsByKey = shortcutSymbols.associateBy { it.key }

fun shortcutSymbol(key: String): ImageVector = (symbolsByKey[key] ?: shortcutSymbols.first()).vector

/** Decoded pictures, by file name. A file is never rewritten (a new picture gets a new name), so entries never go stale. */
private val imageCache = LruCache<String, ImageBitmap>(16)

/**
 * A custom shortcut's icon: its picture in full colour, or its built-in icon in [tint].
 * Falls back to the built-in icon if the picture can't be read.
 */
@Composable
fun ShortcutGlyph(shortcut: CustomShortcut, tint: Color, size: Dp = 24.dp) {
    val image = shortcut.image
    if (image != null) {
        val context = LocalContext.current.applicationContext
        // null while loading; then the picture, or null again if it couldn't be read.
        val loaded by produceState<Pair<Boolean, ImageBitmap?>>(imageCache.get(image)?.let { true to it } ?: (false to null), image) {
            if (!value.first) {
                val bitmap = withContext(Dispatchers.IO) { ShortcutImages.load(context, image)?.asImageBitmap() }
                bitmap?.let { imageCache.put(image, it) }
                value = true to bitmap
            }
        }
        val (done, bitmap) = loaded
        when {
            bitmap != null -> {
                Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 5)))
                return
            }
            !done -> {
                Box(Modifier.size(size))
                return
            }
        }
    }
    Icon(shortcutSymbol(shortcut.icon), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}
