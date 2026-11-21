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

import android.os.Build
import android.view.ActionMode
import android.widget.Magnifier
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.davidv.translator.Point
import dev.davidv.translator.R

/**
 * Render-only layer for a [WordSelectionState]: pills, end handles, loupe and the floating action
 * bar (Copy / Share / Search on DuckDuckGo / Web search). It has NO pointer input; every gesture
 * comes from [selectionGestureResolver] on the overlay root, which writes into the state.
 */
@Composable
fun WordSelectionVisual(
  state: WordSelectionState,
  modifier: Modifier = Modifier,
  onSelectionChanged: (Boolean) -> Unit = {},
) {
  val context = LocalContext.current
  val view = LocalView.current
  val handlePainter = painterResource(R.drawable.selection_handle)
  val magnifier = remember(view) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Magnifier(view) else null }
  DisposableEffect(magnifier) {
    onDispose { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.dismiss() }
  }

  var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var contentRect by remember { mutableStateOf(android.graphics.Rect()) }
  val rectState = rememberUpdatedState(contentRect)
  val selection = state.selection
  val selectedText = rememberUpdatedState(selection?.text ?: "")
  val hasSelection = state.hasSelection

  LaunchedEffect(hasSelection, state) { onSelectionChanged(hasSelection) }

  // Loupe raised a full bubble-height above the finger.
  LaunchedEffect(state.magnifierAt) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@LaunchedEffect
    val m = magnifier ?: return@LaunchedEffect
    val pos = state.magnifierAt
    val coords = layoutCoords
    if (pos == null || coords == null) {
      m.dismiss()
      return@LaunchedEffect
    }
    val root = coords.localToRoot(pos)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      m.show(root.x, root.y, root.x, root.y - m.height.toFloat())
    } else {
      m.show(root.x, root.y)
    }
  }

  fun recomputeRect() {
    val box = selection?.bounds ?: return
    val coords = layoutCoords ?: return
    val t = fitTransform(coords.size.width.toFloat(), coords.size.height.toFloat(), state.imageWidth, state.imageHeight)
    val tl = coords.localToRoot(Offset(t.mapX(box.left.toFloat()), t.mapY(box.top.toFloat())))
    val br = coords.localToRoot(Offset(t.mapX(box.right.toFloat()), t.mapY(box.bottom.toFloat())))
    contentRect = android.graphics.Rect(tl.x.toInt(), tl.y.toInt(), br.x.toInt(), br.y.toInt())
  }

  var actionMode by remember { mutableStateOf<ActionMode?>(null) }
  LaunchedEffect(hasSelection, state.dragging, state.selStart, state.selEnd) {
    if (hasSelection && !state.dragging) {
      recomputeRect()
      if (actionMode == null) {
        actionMode =
          view.startActionMode(
            SelectionActionModeCallback(
              context = context,
              textProvider = { selectedText.value },
              rectProvider = { rectState.value },
              onFinished = { actionMode = null },
            ),
            ActionMode.TYPE_FLOATING,
          )
      } else {
        actionMode?.invalidateContentRect()
      }
    } else {
      actionMode?.finish()
      actionMode = null
    }
  }
  DisposableEffect(Unit) { onDispose { actionMode?.finish() } }

  Canvas(modifier = modifier.onGloballyPositioned { layoutCoords = it }) {
    val sel = selection ?: return@Canvas
    val t = fitTransform(size.width, size.height, state.imageWidth, state.imageHeight)
    sel.pills.forEach { pill -> drawOrientedPill(pill, t, Color(0x553B82F6)) }
    val handleSize = 26.dp.toPx()

    fun drawHandle(
      p: Point,
      degrees: Float,
    ) {
      withTransform({
        translate(t.mapX(p.x) - handleSize / 2f, t.mapY(p.y) - handleSize)
        rotate(degrees, pivot = Offset(handleSize / 2f, handleSize))
      }) {
        with(handlePainter) { draw(Size(handleSize, handleSize)) }
      }
    }
    drawHandle(sel.startHandle, -135f)
    drawHandle(sel.endHandle, 135f)
  }
}
