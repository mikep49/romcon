package dev.romcon

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.romcon.data.Store
import dev.romcon.data.ThemeMode
import dev.romcon.ui.ControlScreen
import dev.romcon.ui.HomeScreen
import dev.romcon.ui.RomConTheme
import dev.romcon.ui.SettingsScreen
import dev.romcon.ui.isDark

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Match the window background to a forced theme, so there's no flash of the system's colours at launch.
        when (Store(this).loadSettings().theme) {
            ThemeMode.SYSTEM -> Unit
            ThemeMode.LIGHT -> setTheme(R.style.Theme_RomCon_Light)
            ThemeMode.DARK -> setTheme(R.style.Theme_RomCon_Dark)
            ThemeMode.OLED -> setTheme(R.style.Theme_RomCon_Black)
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = settings.theme.isDark()
            // Status and navigation bar icons follow the app's theme, not the phone's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            RomConTheme(settings.theme) { RomConApp(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    private companion object {
        // The scrims enableEdgeToEdge() uses behind three-button navigation by default.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

@Composable
private fun RomConApp(vm: AppViewModel) {
    BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.CONTROL -> ControlScreen(vm)
            Screen.SETTINGS -> SettingsScreen(vm)
        }
    }
}
