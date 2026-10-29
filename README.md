# Cube 3D

A 3D twisty-puzzle game for Android, drawn with OpenGL ES 2.0: classic cubes from 2×2 up to 7×7,
the megaminx family (Kilominx, Megaminx, Master Kilominx, Gigaminx, Elite Kilominx, Teraminx), and unique cubes (the
3×3 mirror cube and the 2×2×4 tower). Swipe a face to
turn a layer, drag outside the cube or use two fingers to look around (twist them to spin it in place), and race the clock from a
random scramble. Kotlin + Jetpack Compose, laid out like the sudoku and minesweeper projects next door.

## Modules

- `core/` – plain Kotlin, no Android: the sticker model, moves, scrambles, the solved check, and the
  `Puzzle` catalog (classic sizes and unique cubes).
- `app/` – the GL renderer and touch handling (`gl/`), a single `GameViewModel`, the Compose
  screens, and DataStore persistence (the solve in progress and per-size stats).

## How it works

Every sticker sits in a fixed slot (cubie center in doubled coordinates plus a face normal), so a
move is a permutation of slots, cached per move. A classic cube counts as solved when each face shows one
color, whatever way the whole cube is turned.

## Unique cubes

Every puzzle turns like an n×n×n cube. A `Puzzle` says how its pieces look (the outer faces of the
solved shape; inner cuts stay on the grid) and what solved means. Each sticker remembers its home
slot, so every piece's home and orientation can be rebuilt and drawn as its own box. The mirror cube
uses this: one chrome color, uneven layers, solved when every piece is home (give or take turning the
whole cube). The 2×2×4 tower is a shape-shifter (`ShapeShift.kt`): its 16 cubies live on a 4×4×4 grid
and any layer of it that holds a cubie turns in quarters, so a tall side turned 90° sticks out
sideways. It is solved when it is a 2×2×4 tower again, any way up, with every face one color. To add another, define it in `Puzzle.kt` and list it in `Puzzle.UNIQUE`; a new look may
also need a draw path in `CubeRenderer`.

## Megaminx family

`Minx.kt` is a general face-turning dodecahedron. Each face has the same cut planes parallel to it;
odd sizes get their stickers by slicing every face with every plane, and a turn moves the stickers
whose centroids sit in the turning slab, matched up after a 72° rotation. While a layer turns, the
dark solid is drawn twice, clipped to the turning slab and to the rest, with back faces showing dark
through the cut.

Even sizes (Kilominx, Master Kilominx, Elite Kilominx) have no centers or middle edges, so they turn
like the next odd size but draw each face as five corner kites split into a grid; `MinxTest` checks
that every piece's stickers still move together. Kite edges don't follow the cut planes, so the solid
can't be split along a turn: it stays whole, and the turning kites are rotated on the CPU and pushed
back onto the surface wherever they would sink into it. They slide over the surface, briefly
overlapping their neighbours, rather than clipping through.

Dragging picks whichever face axis moves the touched sticker most nearly along the finger. Turning a
face about its own axis works like a dial: the angle swept around its center.

## Touch

A touch is cast into the cube to find the face under the finger. The first stretch of the drag
picks the layer and direction; the layer then follows the finger and snaps to the nearest quarter
turn on release (a short flick still makes a quarter turn).

## Build

```bash
./gradlew :core:test :app:installDebug
```
