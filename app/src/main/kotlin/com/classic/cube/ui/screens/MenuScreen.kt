package com.classic.cube.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.classic.cube.core.Puzzle
import com.classic.cube.gl.MinxColors
import com.classic.cube.data.SizeStats
import com.classic.cube.game.GameViewModel
import com.classic.cube.ui.theme.AppIcons
import com.classic.cube.ui.theme.LocalKeys
import kotlin.math.cos
import kotlin.math.hypot

@Composable
fun MenuScreen(
    vm: GameViewModel,
    onPlay: (puzzle: Puzzle, scramble: Boolean) -> Unit,
    onResume: () -> Unit,
) {
    val saved by vm.saved.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val puzzle by vm.menuPuzzle.collectAsStateWithLifecycle()
    var helpOpen by remember { mutableStateOf(false) }
    val keys = LocalKeys.current

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { helpOpen = true }) {
                Icon(AppIcons.Help, null, Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("How to Play")
            }
        }
        Spacer(Modifier.weight(1f))
        if (puzzle.isMinx) MinxPicture(puzzle, Modifier.size(140.dp)) else IsoCube(puzzle, Modifier.size(140.dp))
        Spacer(Modifier.height(16.dp))
        Text("Cube 3D", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        SectionLabel("Classic")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Puzzle.CLASSIC) {
                Chip("${p.n}", p == puzzle, Modifier.size(40.dp)) { vm.setMenuPuzzle(p) }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionLabel("Megaminx")
        // Layers per face, like the classic sizes; the full name shows once one is picked.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Puzzle.MINX_FAMILY) {
                Chip("${p.n}", p == puzzle, Modifier.size(40.dp)) { vm.setMenuPuzzle(p) }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionLabel("Unique")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Puzzle.UNIQUE) {
                Chip(p.name, p == puzzle, Modifier.height(40.dp).widthIn(min = 88.dp)) { vm.setMenuPuzzle(p) }
            }
        }
        Spacer(Modifier.height(16.dp))
        StatsRow(stats[puzzle.id] ?: SizeStats())
        Spacer(Modifier.weight(1f))

        val wide = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        saved?.let { s ->
            Button(onClick = onResume, modifier = wide.height(64.dp), shape = RoundedCornerShape(14.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Continue", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${s.state.puzzle.label} · ${formatTime(s.elapsedMillis)} · ${s.moves} move${if (s.moves == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val secondary = ButtonDefaults.buttonColors(containerColor = keys.key, contentColor = MaterialTheme.colorScheme.primary)
        Button(
            onClick = { onPlay(puzzle, true) },
            modifier = wide.height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = if (saved == null) ButtonDefaults.buttonColors() else secondary,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp),
        ) {
            Icon(AppIcons.Shuffle, null)
            Spacer(Modifier.width(8.dp))
            Text("Scramble ${puzzle.label}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onPlay(puzzle, false) }, modifier = wide.height(48.dp)) {
            Text("Free play", fontSize = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("Got it") } },
            title = { Text("How to Play") },
            text = {
                Text(
                    "Make every face one color. A mirror cube is one color all over: bring it back to a cube shape.\n\n" +
                        "• A megaminx turns in fifths. Swipe along the way you want the stickers to move; the closest turn wins.\n" +
                        "• Swipe across the cube to turn a layer. Let go and it snaps to the nearest quarter turn.\n" +
                        "• Drag outside the cube, or with two fingers, to look around it. Twist two fingers to spin it in place.\n" +
                        "• The clock starts with your first turn after the scramble.\n" +
                        "• Undo takes back a turn. Rewind plays every move back to solved, but ends the timed solve.",
                )
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val keys = LocalKeys.current
    Box(
        modifier.clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else keys.key)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatsRow(s: SizeStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Stat("Best", s.bestMillis?.let { formatTime(it, tenths = true) } ?: "–")
        Stat("Average", s.averageMillis?.let { formatTime(it) } ?: "–")
        Stat("Fewest", s.bestMoves?.toString() ?: "–")
        Stat("Solves", s.solves.toString())
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [puzzle] seen straight down onto its top face, drawn from the real sticker layout. */
@Composable
fun MinxPicture(puzzle: Puzzle, modifier: Modifier = Modifier) {
    val g = puzzle.minx
    Canvas(modifier) {
        // The silhouette from above reaches out to the upper ring's outer corners.
        val reach = g.faces.flatten().filter { it[1] > 0 }.maxOf { hypot(it[0], it[2]) }
        val scale = size.minDimension / 2 / reach.toFloat() * 0.97f
        val c = Offset(size.width / 2, size.height / 2)
        fun at(p: DoubleArray) = c + Offset(p[0].toFloat(), p[2].toFloat()) * scale
        val outline = g.faces.indices.filter { g.normals[it][1] > 0 }
        for (f in outline) {
            val pts = g.faces[f].map(::at)
            drawPath(Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (q in pts.drop(1)) lineTo(q.x, q.y)
                close()
            }, Color(0xFF0D1017))
        }
        for (st in g.stickers) {
            if (g.normals[st.face][1] <= 0) continue
            val col = MinxColors[st.face]
            val pts = st.polygon.map { v -> at(DoubleArray(3) { st.centroid[it] + (v[it] - st.centroid[it]) * 0.86 }) }
            drawPath(Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (q in pts.drop(1)) lineTo(q.x, q.y)
                close()
            }, Color(col[0], col[1], col[2]))
        }
    }
}

/**
 * A flat isometric picture of [puzzle] matching the launcher icon: its box seen from above the
 * front-right corner, with colored stickers for regular cubes and chrome plates for a mirror cube.
 */
@Composable
fun IsoCube(puzzle: Puzzle, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val dims = puzzle.dims
        val lo = FloatArray(3) { puzzle.lo(it, 0) }
        val hi = FloatArray(3) { puzzle.hi(it, dims[it] - 1) }
        val k = cos(Math.PI / 6).toFloat()
        // Isometric: x runs down-right, z down-left, y straight up.
        fun flat(x: Float, y: Float, z: Float) = Offset((x - z) * k, (x + z) / 2 - y)
        val corners = (0 until 8).map { i ->
            flat(if (i and 1 == 0) lo[0] else hi[0], if (i and 2 == 0) lo[1] else hi[1], if (i and 4 == 0) lo[2] else hi[2])
        }
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        val scale = minOf(size.width / (maxX - minX), size.height / (maxY - minY)) * 0.96f
        val shift = Offset(size.width / 2 - (minX + maxX) / 2 * scale, size.height / 2 - (minY + maxY) / 2 * scale)
        fun at(p: FloatArray) = flat(p[0], p[1], p[2]) * scale + shift
        fun quad(pts: List<FloatArray>, color: Color) = drawPath(Path().apply {
            val o = pts.map(::at)
            moveTo(o[0].x, o[0].y)
            for (q in o.drop(1)) lineTo(q.x, q.y)
            close()
        }, color)

        val mirror = puzzle.kind == Puzzle.Kind.MIRROR
        // The three faces in view: normal axis, the two axes across it, and the color.
        val faces = listOf(
            Triple(1, 0 to 2, if (mirror) Color(0xFFE9ECF2) else Color(0xFFF4F4F0)),
            Triple(2, 0 to 1, if (mirror) Color(0xFFB9BEC8) else Color(0xFF00A651)),
            Triple(0, 2 to 1, if (mirror) Color(0xFF8C93A0) else Color(0xFFC8102E)),
        )
        val gap = 0.08f
        for ((normal, across, _) in faces) {
            val (a, b) = across
            fun p(u: Float, v: Float) = FloatArray(3).also { it[normal] = hi[normal]; it[a] = u; it[b] = v }
            quad(listOf(p(lo[a], lo[b]), p(hi[a], lo[b]), p(hi[a], hi[b]), p(lo[a], hi[b])), Color(0xFF0D1017))
        }
        for ((normal, across, color) in faces) {
            val (a, b) = across
            fun p(u: Float, v: Float) = FloatArray(3).also { it[normal] = hi[normal]; it[a] = u; it[b] = v }
            for (i in 0 until dims[a]) for (j in 0 until dims[b]) {
                val u0 = puzzle.lo(a, i) + gap
                val u1 = puzzle.hi(a, i) - gap
                val v0 = puzzle.lo(b, j) + gap
                val v1 = puzzle.hi(b, j) - gap
                quad(listOf(p(u0, v0), p(u1, v0), p(u1, v1), p(u0, v1)), color)
            }
        }
    }
}

fun formatTime(millis: Long, tenths: Boolean = false): String {
    val total = millis / 1000
    val m = total / 60
    val s = total % 60
    val base = if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
    return if (tenths) "$base.${millis / 100 % 10}" else base
}
