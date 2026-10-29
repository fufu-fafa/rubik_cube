package com.classic.cube.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.classic.cube.game.GameViewModel
import com.classic.cube.game.Phase
import com.classic.cube.gl.CubeView
import com.classic.cube.ui.theme.AppIcons
import com.classic.cube.ui.theme.LocalKeys

@Composable
fun GameScreen(vm: GameViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bg = MaterialTheme.colorScheme.background
    var view by remember { mutableStateOf<CubeView?>(null) }
    var confirmRewind by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        view?.onResume()
        vm.setVisible(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        view?.onPause()
        vm.setVisible(false)
    }
    LaunchedEffect(ui.moves, ui.phase) {
        if (ui.phase == Phase.RUNNING && ui.moves > 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        if (ui.phase == Phase.SOLVED) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> CubeView(ctx, vm.session).also { view = it } },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.renderer.clearColor = floatArrayOf(bg.red, bg.green, bg.blue)
                v.requestRender()
            },
            onRelease = { v ->
                v.onPause()
                view = null
            },
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                Text(ui.puzzle.label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatTime(ui.elapsedMillis, tenths = true),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (ui.phase == Phase.RUNNING || ui.phase == Phase.SOLVED) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Text(
                    when (ui.phase) {
                        Phase.FREE -> "Free play"
                        Phase.SCRAMBLING -> "Scrambling…"
                        Phase.READY -> "Clock starts on your first turn"
                        Phase.RUNNING -> "${ui.moves} move${if (ui.moves == 1) "" else "s"}"
                        Phase.SOLVED -> "Solved in ${ui.moves} moves"
                        Phase.REWINDING -> "Rewinding…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(AppIcons.Undo, "Undo", ui.canUndo) { vm.undo() }
                ToolButton(AppIcons.Rewind, "Rewind", ui.canRewind) {
                    if (ui.phase == Phase.RUNNING || ui.phase == Phase.READY) confirmRewind = true else vm.rewind()
                }
                ToolButton(AppIcons.ThreeD, "Reset view", true) { vm.resetCamera() }
                ToolButton(AppIcons.Shuffle, "Scramble", ui.phase != Phase.SCRAMBLING) { vm.newGame(ui.puzzle, scramble = true) }
            }
        }

        AnimatedVisibility(
            visible = ui.result != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val r = ui.result
            if (r != null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = LocalKeys.current.key,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Solved!", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        if (r.newBest) {
                            Text("New best time", color = LocalKeys.current.gold, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(formatTime(r.millis, tenths = true), fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("${r.moves} moves", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        r.previousBest?.takeIf { !r.newBest }?.let {
                            Text("Best ${formatTime(it, tenths = true)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { vm.newGame(ui.puzzle, scramble = true) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("Scramble again", fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = vm::dismissResult, modifier = Modifier.fillMaxWidth()) { Text("Admire it") }
                    }
                }
            }
        }
    }

    if (confirmRewind) {
        AlertDialog(
            onDismissRequest = { confirmRewind = false },
            title = { Text("Give up?") },
            text = { Text("Rewinding plays every move back to solved. This solve won't count.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRewind = false
                    vm.rewind()
                }) { Text("Rewind") }
            },
            dismissButton = { TextButton(onClick = { confirmRewind = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val keys = LocalKeys.current
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp).background(keys.key, RoundedCornerShape(16.dp)),
        ) { Icon(icon, label, tint = tint) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
