package com.classic.cube

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.classic.cube.game.GameViewModel
import com.classic.cube.ui.screens.GameScreen
import com.classic.cube.ui.screens.MenuScreen
import com.classic.cube.ui.theme.CubeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CubeApp
        setContent {
            val vm: GameViewModel = viewModel(
                factory = viewModelFactory { initializer { GameViewModel(app.prefs, app.savedGames, app.stats) } },
            )
            CubeTheme(isSystemInDarkTheme()) {
                Surface(color = MaterialTheme.colorScheme.background) { App(vm) }
            }
        }
    }
}

@Composable
private fun App(vm: GameViewModel) {
    // Only the menu survives process death: the game screen needs the view model's live cube.
    var inGame by rememberSaveable { mutableStateOf(false) }
    val toMenu = {
        vm.setVisible(false)
        inGame = false
    }
    BackHandler(enabled = inGame, onBack = toMenu)
    if (inGame) {
        GameScreen(vm, onBack = toMenu)
    } else {
        MenuScreen(
            vm,
            onPlay = { puzzle, scramble ->
                vm.newGame(puzzle, scramble)
                inGame = true
            },
            onResume = {
                vm.saved.value?.let { vm.resume(it) }
                inGame = true
            },
        )
    }
}
