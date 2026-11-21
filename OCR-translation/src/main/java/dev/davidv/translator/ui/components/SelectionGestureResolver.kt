/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.bindings.selectionNearestWord
import uniffi.bindings.selectionResolve
import uniffi.bindings.selectionWordAt
import uniffi.bindings.selectionWordAxis
import uniffi.translator_core.OrientedRect
import uniffi.translator_core.PositionedWord
import uniffi.translator_core.SelectionView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Selection state shared by the single gesture resolver (input) and [WordSelectionVisual]
 * (render). Words are in image-pixel space. A new instance is created per recognized image.
 */
@Stable
class WordSelectionState(
  val words: List<PositionedWord>,
  val imageWidth: Int,
  val imageHeight: Int,
) {
  var selStart by mutableStateOf<Int?>(null)
    private set
  var selEnd by mutableStateOf<Int?>(null)
    private set

  /** True while a handle/sweep drag is in flight (the action bar is hidden meanwhile). */
  var dragging by mutableStateOf(false)
    internal set

  /** Finger position (view-local px) for the loupe; null hides it. */
  var magnifierAt by mutableStateOf<Offset?>(null)
    internal set

  val hasSelection: Boolean get() = selStart != null && selEnd != null

  /** Pills, handles, bounds and text for the current selection, resolved by translator-rs. */
  val selection: SelectionView? by derivedStateOf {
    val s = selStart
    val e = selEnd
    if (s != null && e != null) selectionResolve(words, s.toUInt(), e.toUInt()) else null
  }

  fun clear() {
    selStart = null
    selEnd = null
  }

  fun select(
    a: Int,
    b: Int,
  ) {
    selStart = min(a, b)
    selEnd = max(a, b)
  }
}

/** What the resolver decided when a lasso closed. */
enum class LassoOutcome {
  /** Words inside the polygon became a text selection; no image crop. */
  TEXT,

  /** No words inside: keep the image crop (reverse image search). */
  IMAGE,

  /** The stroke was shorter than [LASSO_MIN_DP]; discard it. */
  TOO_SMALL,
}

// Constants (dp unless noted), taken from reference implementations.
const val LASSO_MIN_DP = 24f // smallest lasso extent worth acting on
const val LASSO_THIN_DP = 3f // drop stroke points closer than this to the previous one
const val HANDLE_GRAB_DP = 28f // radius to grab a selection handle
const val LASSO_MIN_COVERAGE = 0.30f // fraction of a word's box inside the polygon
const val LASSO_MIN_CORNERS = 2 // corners inside (alternative to centre inside)
const val SAME_LINE_MIN_OVERLAP = 0.30f // sweep: min overlap / min(heights) to count as same line
const val TAP_HIT_TOLERANCE_DP = 4f // tap: slack around the hit word's box

private fun pointInPolygon(
  x: Float,
  y: Float,
  poly: List<Offset>,
): Boolean {
  var inside = false
  var j = poly.size - 1
  for (i in poly.indices) {
    val a = poly[i]
    val b = poly[j]
    if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
    j = i
  }
  return inside
}

/** Point of an oriented rect at local fractions (u, v) in [-0.5, 0.5]. */
private fun OrientedRect.at(
  u: Float,
  v: Float,
): Offset {
  val c = cos(angleRadians)
  val s = sin(angleRadians)
  val lx = u * width
  val ly = v * height
  return Offset(cx + lx * c - ly * s, cy + lx * s + ly * c)
}

/**
 * True when [b] is on the same text line as [a]: along the normal of [a]'s text direction the
 * two boxes' extents overlap by more than [SAME_LINE_MIN_OVERLAP] of the SMALLER height.
 * Using the smaller height tolerates a big title next to a small subtitle (different box
 * heights) while a word on the next line, whose box only touches/grazes this one, fails.
 */
internal fun sameLine(
  a: OrientedRect,
  b: OrientedRect,
): Boolean {
  val nx = -sin(a.angleRadians)
  val ny = cos(a.angleRadians)
  val d = b.angleRadians - a.angleRadians
  val aHalf = a.height / 2f
  // Half extent of b along a's normal (b may be slightly rotated relative to a).
  val bHalf = (kotlin.math.abs(b.width * sin(d)) + kotlin.math.abs(b.height * cos(d))) / 2f
  val aC = a.cx * nx + a.cy * ny
  val bC = b.cx * nx + b.cy * ny
  val overlap = min(aC + aHalf, bC + bHalf) - max(aC - aHalf, bC - bHalf)
  val minH = min(a.height, 2f * bHalf)
  return minH > 0f && overlap > SAME_LINE_MIN_OVERLAP * minH
}

/** True when (x, y) lies in [r] grown by [tol] on every side (oriented-box aware). */
internal fun OrientedRect.containsWithTolerance(
  x: Float,
  y: Float,
  tol: Float,
): Boolean {
  val dx = x - cx
  val dy = y - cy
  val c = cos(angleRadians)
  val s = sin(angleRadians)
  val lx = dx * c + dy * s
  val ly = -dx * s + dy * c
  return kotlin.math.abs(lx) <= width / 2f + tol && kotlin.math.abs(ly) <= height / 2f + tol
}

/**
 * Indices of words that a closed lasso (polygon in image space) selects: box coverage
 * >= [LASSO_MIN_COVERAGE] AND (centre inside OR >= [LASSO_MIN_CORNERS] corners inside).
 * Coverage is estimated by sampling an 8x4 grid over the oriented box.
 */
internal fun wordsInPolygon(
  words: List<PositionedWord>,
  poly: List<Offset>,
): List<Int> {
  if (poly.size < 3) return emptyList()
  val res = ArrayList<Int>()
  words.forEachIndexed { i, w ->
    val r = w.bounds
    val centre = r.at(0f, 0f)
    val centreIn = pointInPolygon(centre.x, centre.y, poly)
    var corners = 0
    for (u in floatArrayOf(-0.5f, 0.5f)) {
      for (v in floatArrayOf(-0.5f, 0.5f)) {
        val p = r.at(u, v)
        if (pointInPolygon(p.x, p.y, poly)) corners++
      }
    }
    if (!centreIn && corners < LASSO_MIN_CORNERS) return@forEachIndexed
    var hit = 0
    val nu = 8
    val nv = 4
    for (iu in 0 until nu) {
      for (iv in 0 until nv) {
        val p = r.at((iu + 0.5f) / nu - 0.5f, (iv + 0.5f) / nv - 0.5f)
        if (pointInPolygon(p.x, p.y, poly)) hit++
      }
    }
    if (hit.toFloat() / (nu * nv) >= LASSO_MIN_COVERAGE) res.add(i)
  }
  return res
}

/**
 * The ONE pointer handler of the overlay root. Decides the user's intent from DOWN to UP:
 *
 *  DOWN -> selection handle hit?            -> resize selection (handle drag)
 *       -> word under finger (Rust hit-test) -> remember word + time
 *  moved past touchSlop?
 *     no  -> UP: word ? select it : clear selection (long press selects the word early)
 *     yes -> started on a word ? sweep range of words : lasso
 *  lasso close -> words inside polygon ? text selection : image crop
 *
 * With `state == null` (no OCR yet) every drag is a lasso. Lasso side effects (path drawing, crop)
 * stay with the caller through the callbacks; this function never refuses to draw.
 */
fun Modifier.selectionGestureResolver(
  state: WordSelectionState?,
  isCropHandleAt: (Offset) -> Boolean,
  isCropResizing: () -> Boolean,
  onLassoStart: (Offset) -> Unit,
  onLassoMove: (PointerInputChange) -> Unit,
  onLassoEnd: (points: List<Offset>, outcome: LassoOutcome) -> Unit,
): Modifier =
  composed {
    val st = state
    val cropHandleAt = rememberUpdatedState(isCropHandleAt)
    val resizing = rememberUpdatedState(isCropResizing)
    val lassoStart = rememberUpdatedState(onLassoStart)
    val lassoMove = rememberUpdatedState(onLassoMove)
    val lassoEnd = rememberUpdatedState(onLassoEnd)
    pointerInput(st) {
      val slop = viewConfiguration.touchSlop
      val longPressMs = viewConfiguration.longPressTimeoutMillis
      val thin = LASSO_THIN_DP.dp.toPx()
      val minLasso = LASSO_MIN_DP.dp.toPx()
      val handleRadius = HANDLE_GRAB_DP.dp.toPx()
      val tapTol = TAP_HIT_TOLERANCE_DP.dp.toPx()

      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val t = fitTransformOrNull(st, size.width.toFloat(), size.height.toFloat())

        // 1) Existing selection handle -> adjust the selection.
        val cur = st?.selection
        if (st != null && t != null && cur != null) {
          fun dist(p: uniffi.translator_core.Point) =
            (down.position - Offset(t.mapX(p.x), t.mapY(p.y))).getDistance()
          val fixedIsEnd =
            when {
              dist(cur.startHandle) < handleRadius -> true
              dist(cur.endHandle) < handleRadius -> false
              else -> null
            }
          if (fixedIsEnd != null) {
            down.consume()
            val s0 = st.selStart!!
            val e0 = st.selEnd!!
            val fixed = if (fixedIsEnd) e0 else s0
            val axis = selectionWordAxis(st.words, fixed.toUInt())
            st.dragging = true
            while (true) {
              val ev = awaitPointerEvent()
              val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
              if (!ch.pressed) break
              ch.consume()
              selectionNearestWord(st.words, t.unmapX(ch.position.x), t.unmapY(ch.position.y), axis)
                ?.let { st.select(fixed, it.toInt()) }
              st.magnifierAt = ch.position
            }
            st.dragging = false
            st.magnifierAt = null
            return@awaitEachGesture
          }
        }

        // 2) DOWN: hit-test a word (unless a crop-frame handle owns this spot).
        val onCrop = cropHandleAt.value(down.position)
        val startWord =
          if (st != null && t != null && !onCrop) {
            val ix = t.unmapX(down.position.x)
            val iy = t.unmapY(down.position.y)
            // Guard: the hit-test may return a neighbouring word; only trust it when its box
            // really contains the touch point (small tolerance), else treat as "no word".
            selectionWordAt(st.words, ix, iy)?.toInt()?.takeIf {
              st.words[it].bounds.containsWithTolerance(ix, iy, tapTol / t.scale)
            }
          } else {
            null
          }

        // 3) Wait for slop / UP / long press.
        var moved = false
        var longPressed = false
        var lastChange: PointerInputChange = down
        while (true) {
          val remaining = longPressMs - (lastChange.uptimeMillis - down.uptimeMillis)
          val ev =
            if (startWord != null && !longPressed) {
              withTimeoutOrNull(max(remaining, 1L)) { awaitPointerEvent() }
            } else {
              awaitPointerEvent()
            }
          if (ev == null) {
            // Held on a word past the long-press timeout: select it right away.
            longPressed = true
            st!!.select(startWord!!, startWord)
            continue
          }
          val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
          lastChange = ch
          if (!ch.pressed) break
          if ((ch.position - down.position).getDistance() > slop) {
            moved = true
            break
          }
        }

        if (!moved) {
          // Tap: word -> select, empty space -> clear.
          if (st != null && !onCrop) {
            if (startWord != null) st.select(startWord, startWord) else st.clear()
          }
          return@awaitEachGesture
        }

        if (st != null && t != null && startWord != null) {
          // 4a) Drag from a word: sweep a range of words.
          val axis = selectionWordAxis(st.words, startWord.toUInt())
          val startBounds = st.words[startWord].bounds
          st.select(startWord, startWord)
          st.dragging = true
          var ch = lastChange
          while (true) {
            ch.consume()
            // Same-line guard: a candidate from another line (e.g. under a smaller font) is
            // dropped and the previous selection kept.
            selectionNearestWord(st.words, t.unmapX(ch.position.x), t.unmapY(ch.position.y), axis)
              ?.toInt()
              ?.takeIf { sameLine(startBounds, st.words[it].bounds) }
              ?.let { st.select(startWord, it) }
            st.magnifierAt = ch.position
            val ev = awaitPointerEvent()
            ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
          }
          st.dragging = false
          st.magnifierAt = null
          return@awaitEachGesture
        }

        // 4b) Drag from empty space: lasso. Never refused because text lies underneath.
        st?.clear()
        lassoStart.value(down.position)
        val pts = ArrayList<Offset>()
        pts.add(down.position)
        var ch = lastChange
        var lastPos = down.position
        while (true) {
          ch.consume()
          lassoMove.value(ch)
          if ((ch.position - pts.last()).getDistance() >= thin) pts.add(ch.position)
          lastPos = ch.position
          val ev = awaitPointerEvent()
          ch = ev.changes.firstOrNull { it.id == down.id } ?: break
          if (!ch.pressed) break
        }
        if (pts.last() != lastPos) pts.add(lastPos)

        val outcome =
          if (resizing.value()) {
            LassoOutcome.IMAGE // frame resize: caller finalizes the crop
          } else {
            val w = pts.maxOf { it.x } - pts.minOf { it.x }
            val h = pts.maxOf { it.y } - pts.minOf { it.y }
            if (max(w, h) < minLasso) {
              LassoOutcome.TOO_SMALL
            } else if (st != null && t != null) {
              val poly = pts.map { Offset(t.unmapX(it.x), t.unmapY(it.y)) }
              val idx = wordsInPolygon(st.words, poly)
              if (idx.isNotEmpty()) {
                st.select(idx.first(), idx.last())
                LassoOutcome.TEXT
              } else {
                LassoOutcome.IMAGE
              }
            } else {
              LassoOutcome.IMAGE
            }
          }
        lassoEnd.value(pts, outcome)
      }
    }
  }

private fun fitTransformOrNull(
  st: WordSelectionState?,
  w: Float,
  h: Float,
): FitTransform? = if (st == null || st.imageWidth <= 0 || st.imageHeight <= 0) null else fitTransform(w, h, st.imageWidth, st.imageHeight)
