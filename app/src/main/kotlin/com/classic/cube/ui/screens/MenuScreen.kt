package com.classic.cube.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.classic.cube.core.Solid
import com.classic.cube.gl.minxColors
import com.classic.cube.data.SizeStats
import com.classic.cube.game.GameViewModel
import com.classic.cube.ui.theme.AppIcons
import com.classic.cube.ui.theme.LocalKeys
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The puzzle families the menu switches between, each remembering its own pick. */
private enum class Family(val label: String, val puzzles: List<Puzzle>, val first: Puzzle) {
    CLASSIC("Classic", Puzzle.CLASSIC, Puzzle.standard(3)),
    MEGAMINX("Megaminx", Puzzle.MINX_FAMILY, Puzzle.MINX_FAMILY.first { it.n == 3 }),
    PYRAMINX("Pyraminx", Puzzle.PYRAMINX_FAMILY, Puzzle.PYRAMINX_FAMILY.first()),
    UNIQUE("Unique", Puzzle.UNIQUE, Puzzle.UNIQUE.first());

    /** The menu heading for [p]: the family and size, or a unique puzzle's own name. */
    fun title(p: Puzzle) = when (this) {
        CLASSIC -> "Classic ${p.n}×${p.n}"
        MEGAMINX, PYRAMINX -> "$label ${p.n}"
        UNIQUE -> p.name
    }

    companion object {
        fun of(p: Puzzle) = entries.first { p in it.puzzles }
    }
}

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
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    val family = Family.of(puzzle)
    // What each family showed last, so switching tabs and back keeps the size.
    val picks = remember { mutableStateMapOf<Family, Puzzle>() }
    picks[family] = puzzle
    val resumable = saved?.takeIf { it.state.puzzle == puzzle }
    val colors = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cube 3D", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { helpOpen = true }, modifier = Modifier.offset(x = 10.dp)) {
                    Icon(AppIcons.Help, "How to play", tint = colors.primary)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val art = Modifier.size(264.dp)
                if (puzzle.isMinx) MinxPicture(puzzle, art) else IsoCube(puzzle, art)
            }
            Text(
                family.title(puzzle),
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            StatsToggle(stats[puzzle.id] ?: SizeStats(), statsOpen) { statsOpen = !statsOpen }

            FamilyTabs(family, Modifier.padding(top = 12.dp)) { f -> vm.setMenuPuzzle(picks[f] ?: f.first) }
            Row(
                Modifier.padding(top = 16.dp).height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (p in family.puzzles) {
                    val label = if (family == Family.UNIQUE) p.name else "${p.n}"
                    val width = if (family == Family.UNIQUE) 116.dp else 48.dp
                    SizeChip(label, p == puzzle, Modifier.width(width)) { vm.setMenuPuzzle(p) }
                }
            }

            Button(
                onClick = { if (resumable != null) onResume() else onPlay(puzzle, true) },
                modifier = Modifier.padding(top = 24.dp).fillMaxWidth().height(68.dp),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (resumable != null) "Continue" else "Play", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (resumable != null) {
                        Text(
                            "${formatTime(resumable.elapsedMillis)} · ${resumable.moves} move${if (resumable.moves == 1) "" else "s"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Box(Modifier.padding(top = 4.dp).height(48.dp), contentAlignment = Alignment.Center) {
                if (resumable != null) {
                    TextButton(onClick = { onPlay(puzzle, true) }, modifier = Modifier.height(48.dp)) {
                        Icon(AppIcons.Shuffle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New scramble", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            confirmButton = { TextButton(onClick = { helpOpen = false }) { Text("Got it") } },
            title = { Text("How to Play") },
            text = {
                Text(
                    "Make every face one color. A mirror cube is one color all over: bring it back to a cube shape.\n\n" +
                        "• A megaminx turns in fifths and a pyraminx in thirds. Swipe along the way you want the stickers to move; the closest turn wins. " +
                            "A straight swipe along a row turns the neighbouring face; to spin a megaminx face itself, drag in an arc around its center.\n" +
                        "• Swipe across the cube to turn a layer. Let go and it snaps to the nearest quarter turn.\n" +
                        "• Drag outside the cube, or with two fingers, to look around it. Twist two fingers to spin it in place.\n" +
                        "• The clock starts with your first turn after the scramble.\n" +
                        "• Undo takes back a turn. Rewind plays every move back to solved, but ends the timed solve.",
                )
            },
        )
    }
}

/** One line of stats: best time and solves, or every stat once tapped open. */
@Composable
private fun StatsToggle(s: SizeStats, open: Boolean, onToggle: () -> Unit) {
    // No-break spaces inside each stat, so a long line only wraps between stats.
    val best = "Best\u00A0${s.bestMillis?.let { formatTime(it, tenths = true) } ?: "–"}"
    val solves = "${s.solves}\u00A0solve${if (s.solves == 1) "" else "s"}"
    val text = if (open) {
        "$best · Average\u00A0${s.averageMillis?.let { formatTime(it) } ?: "–"} · Fewest\u00A0${s.bestMoves ?: "–"} · $solves"
    } else {
        "$best · $solves"
    }
    Row(
        Modifier.heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = if (open) "Show fewer stats" else "Show all stats", onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Every stat can run past one line on a narrow phone; wrap it centered.
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!open) Icon(AppIcons.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A segmented control for the puzzle families. */
@Composable
private fun FamilyTabs(selected: Family, modifier: Modifier, onPick: (Family) -> Unit) {
    val keys = LocalKeys.current
    Row(
        modifier.fillMaxWidth().background(keys.track, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (f in Family.entries) {
            val on = f == selected
            Box(
                Modifier.weight(1f).height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) keys.trackSelected else Color.Transparent)
                    .clickable(role = Role.Tab) { onPick(f) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    f.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SizeChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val keys = LocalKeys.current
    Box(
        modifier.height(48.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else keys.key)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** [puzzle] seen straight down onto its top face, drawn from the real sticker layout. */
@Composable
fun MinxPicture(puzzle: Puzzle, modifier: Modifier = Modifier) {
    val g = puzzle.minx
    val colors = minxColors(g)
    Canvas(modifier) {
        // The silhouette from above reaches out to the farthest corner of a face in view.
        val outline = g.faces.indices.filter { g.normals[it][1] > 0 }
        val reach = outline.flatMap { g.faces[it] }.maxOf { hypot(it[0], it[2]) }
        val scale = size.minDimension / 2 / reach.toFloat() * 0.97f
        val c = Offset(size.width / 2, size.height / 2)
        // A pyraminx is spun so a corner points straight up.
        val spin = if (g.solid != Solid.TETRAHEDRON) 0.0 else {
            val top = outline.flatMap { g.faces[it] }.filter { hypot(it[0], it[2]) > reach - 1e-6 }.minBy { it[2] }
            -atan2(top[0], -top[2])
        }
        val cs = cos(spin)
        val sn = sin(spin)
        fun at(p: DoubleArray) = c + Offset((p[0] * cs - p[2] * sn).toFloat(), (p[0] * sn + p[2] * cs).toFloat()) * scale
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
            val col = colors[st.face]
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
