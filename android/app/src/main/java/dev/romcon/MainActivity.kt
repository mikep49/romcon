package dev.romcon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.romcon.ui.ControlScreen
import dev.romcon.ui.HomeScreen
import dev.romcon.ui.RomConTheme
import dev.romcon.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RomConTheme { RomConApp(vm) }
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
