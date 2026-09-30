package com.photostretcher.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.photostretcher.app.model.Axis
import com.photostretcher.app.ui.theme.Accent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/** Hit test result meaning "the finger is inside the band, drag the whole band". */
private const val TARGET_BAND = -1

/** A straight strip of the source picture, drawn into the result. */
internal data class Strip(
    val srcStart: Int,
    val srcSize: Int,
    /** Distance from the top (or left) of the drawn result, in screen pixels. */
    val dstStart: Float,
    val dstSize: Float,
)

/**
 * Where everything ends up on screen.
 *
 * The editor only ever deals in fractions (0f..1f) of the picture, exactly like
 * [com.photostretcher.app.model.StretchOp] does. This class is the single place that turns those
 * fractions into pixels and it is used both for drawing and for reading finger positions back,
 * so the two can never disagree.
 */
class ResultLayout(
    val axis: Axis,
    val baseWidth: Int,
    val baseHeight: Int,
    /** The space the picture may use, in pixels. */
    val area: Rect,
    val bandStart: Float,
    val bandEnd: Float,
    val factor: Float,
) {
    val baseLength: Float = (if (axis == Axis.VERTICAL) baseHeight else baseWidth).toFloat()
    private val baseCross: Float = (if (axis == Axis.VERTICAL) baseWidth else baseHeight).toFloat()

    private val aSize = bandStart * baseLength
    private val bSize = (bandEnd - bandStart) * baseLength
    private val cSize = (1f - bandEnd) * baseLength

    /** Length of the stretched picture, measured in pixels of the source picture. */
    val outLength = aSize + bSize * factor + cSize

    private val available = if (axis == Axis.VERTICAL) area.height else area.width

    /**
     * The result is drawn at its natural size and only shrunk when it no longer fits the area.
     * A factor of exactly 1 means the untouched parts above and below the band are blitted 1:1,
     * which is what makes the stretch look like a real cut rather than a resize.
     */
    val scale: Float = if (outLength > 0f && baseLength > 0f) min(1f, available / outLength) else 1f

    /** Top left corner of the drawn result. */
    val origin: Offset = if (axis == Axis.VERTICAL) {
        Offset(area.left + (area.width - baseCross * scale) / 2f, area.top)
    } else {
        Offset(area.left, area.top + (area.height - baseCross * scale) / 2f)
    }

    val crossStart: Float get() = if (axis == Axis.VERTICAL) origin.x else origin.y
    val axisStart: Float get() = if (axis == Axis.VERTICAL) origin.y else origin.x
    val crossSize: Float get() = baseCross * scale

    /** Where the drawn band starts. */
    val bandTopScreen: Float get() = axisStart + aSize * scale

    /** Where the drawn band ends. */
    val bandBottomScreen: Float get() = bandTopScreen + bSize * factor * scale

    /** Screen position of a [fraction] of the picture. */
    fun fractionToScreen(fraction: Float): Float = axisStart + fraction * baseLength * scale

    /** The other way round: which fraction of the picture sits at this screen position. */
    fun screenToFraction(position: Float): Float =
        ((position - axisStart) / (baseLength * scale)).coerceIn(0f, 1f)

    fun axisOf(point: Offset): Float = if (axis == Axis.VERTICAL) point.y else point.x

    /** Middle of the line drawn at [fraction]. */
    fun handleAt(fraction: Float): Offset = if (axis == Axis.VERTICAL) {
        Offset(crossStart + crossSize / 2f, fractionToScreen(fraction))
    } else {
        Offset(fractionToScreen(fraction), crossStart + crossSize / 2f)
    }

    /**
     * The three strips of the technical rule: everything above the band, the band itself,
     * everything below. Strips too thin to see are still returned, the caller skips them.
     */
    fun strips(): List<Strip> = listOf(
        Strip(0, aSize.toInt(), 0f, aSize * scale),
        Strip(aSize.toInt(), bSize.toInt(), aSize * scale, bSize * factor * scale),
        Strip((aSize + bSize).toInt(), cSize.toInt(), (aSize + bSize * factor) * scale, cSize * scale),
    )

    /** Width of the soft seam blend used by the "smooth edges" option, in screen pixels. */
    val seamPixels: Float get() = (outLength * scale / 300f).coerceIn(2f, 8f)
}

/** Everything a gesture needs, kept in a [androidx.compose.runtime.State] so drags survive recomposition. */
private data class CanvasInput(
    val layout: ResultLayout,
    val lines: List<Float>,
    val touchRadius: Float,
    val onTap: (Float) -> Unit,
    val onDragLine: (Int, Float) -> Unit,
    val onDragBand: (Float) -> Unit,
)

/** Which line a finger grabbed, [TARGET_BAND], or `null` when it grabbed nothing. */
private fun hitTest(input: CanvasInput, position: Offset): Int? {
    if (input.lines.isEmpty()) return null
    val at = input.layout.axisOf(position)
    input.lines.forEachIndexed { index, fraction ->
        if (abs(at - input.layout.fractionToScreen(fraction)) <= input.touchRadius) return index
    }
    if (input.lines.size < 2) return null
    return if (at in input.layout.bandTopScreen..input.layout.bandBottomScreen) TARGET_BAND else null
}

/**
 * The editable picture: the live stretch preview plus the two draggable lines.
 *
 * Gestures:
 *  - tap anywhere to place the first line, then the second, then to move whichever line is nearest
 *  - drag a line or its handle to move that line
 *  - drag inside the band to move the whole band
 */
@Composable
fun PhotoCanvas(
    image: ImageBitmap,
    modifier: Modifier = Modifier,
    axis: Axis,
    lines: List<Float>,
    factor: Float,
    smoothEdges: Boolean,
    onTap: (Float) -> Unit,
    onDragLine: (Int, Float) -> Unit,
    onDragBand: (Float) -> Unit,
    accent: Color = Accent,
    fadeSteps: Int = 6,
) {
    val density = LocalDensity.current
    val touchRadius = with(density) { 26.dp.toPx() }
    val smooth = smoothEdges && lines.size == 2

    BoxWithConstraints(modifier) {
        val area = with(density) {
            Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx())
        }
        val layout = ResultLayout(
            axis = axis,
            baseWidth = image.width,
            baseHeight = image.height,
            area = area,
            bandStart = lines.getOrElse(0) { 0f },
            bandEnd = lines.getOrElse(1) { lines.firstOrNull() ?: 0f },
            factor = factor,
        )
        val input = rememberUpdatedState(
            CanvasInput(layout, lines, touchRadius, onTap, onDragLine, onDragBand),
        )

        val gestures = Modifier.pointerInput(image, axis) {
            awaitEachGesture {
                val current = input.value
                val layoutNow = current.layout
                val down = awaitFirstDown(requireUnconsumed = false)
                val target = hitTest(current, down.position)
                var moved = false
                var fraction = layoutNow.screenToFraction(layoutNow.axisOf(down.position))

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                    }
                    if (moved) {
                        val before = fraction
                        fraction = layoutNow.screenToFraction(layoutNow.axisOf(change.position))
                        when {
                            target == null -> Unit // dragging empty space changes nothing
                            target == TARGET_BAND -> current.onDragBand(fraction - before)
                            else -> current.onDragLine(target, fraction)
                        }
                        change.consume()
                    }
                }
                if (!moved) current.onTap(fraction)
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val vertical = axis == Axis.VERTICAL
            val lineWidth = 2.dp.toPx()
            val handleRadius = 11.dp.toPx()
            val overhang = 14.dp.toPx()

            // Integer destination edges. Rounding the *boundaries* rather than each strip's own
            // size is what keeps two neighbouring strips flush against each other, so no hairline
            // gap can appear between them however the fractions fall.
            val crossFrom = round(layout.crossStart).toInt()
            val crossTo = round(layout.crossStart + layout.crossSize).toInt().coerceAtLeast(crossFrom + 1)
            val crossLength = crossTo - crossFrom

            fun drawStrip(strip: Strip, alpha: Float = 1f) {
                if (strip.srcSize <= 0 || strip.dstSize <= 0f) return
                val from = round(layout.axisStart + strip.dstStart).toInt()
                val to = round(layout.axisStart + strip.dstStart + strip.dstSize)
                    .toInt().coerceAtLeast(from + 1)
                val length = to - from
                val srcOffset = if (vertical) {
                    IntOffset(strip.srcStart, 0)
                } else {
                    IntOffset(0, strip.srcStart)
                }
                val srcSize = if (vertical) {
                    IntSize(strip.srcSize, image.height)
                } else {
                    IntSize(image.width, strip.srcSize)
                }
                val dstOffset = if (vertical) {
                    IntOffset(crossFrom, from)
                } else {
                    IntOffset(from, crossFrom)
                }
                val dstSize = if (vertical) {
                    IntSize(crossLength, length)
                } else {
                    IntSize(length, crossLength)
                }
                drawImage(
                    image = image,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = alpha,
                )
            }

            val strips = layout.strips()
            strips.forEach { drawStrip(it) }

            // Soft seams: the strips on both sides of a cut are redrawn over a few pixels with
            // opposite alpha ramps, which cross fades the two textures instead of cutting.
            if (smooth) {
                for (seam in 0..1) {
                    val above = strips[seam]
                    val below = strips[seam + 1]
                    if (above.srcSize <= 0 || below.srcSize <= 0) continue
                    val fade = min(layout.seamPixels, min(above.dstSize, below.dstSize))
                    if (fade < 1f) continue
                    val fadeSource = min(fade, min(above.srcSize.toFloat(), below.srcSize.toFloat()))
                    val aboveSource = above.srcStart + (above.srcSize - fadeSource).toInt()
                    val aboveEdge = above.dstStart + above.dstSize
                    // Strips are drawn on whole pixels, so never plan for more steps than the
                    // seam has pixels, otherwise every step would snap to 1px and the blend would
                    // end up wider than the seam it is meant to cover.
                    val steps = min(fadeSteps, fade.toInt().coerceAtLeast(1))
                    for (step in 0 until steps) {
                        val from = step / steps.toFloat()
                        val to = (step + 1) / steps.toFloat()
                        val middle = (from + to) / 2f
                        drawStrip(
                            Strip(
                                aboveSource + (fadeSource * from).toInt(),
                                ((fadeSource * (to - from)).toInt()).coerceAtLeast(1),
                                aboveEdge - fade + fade * from,
                                fade * (to - from),
                            ),
                            alpha = 1f - middle,
                        )
                        drawStrip(
                            Strip(
                                below.srcStart + (fadeSource * from).toInt(),
                                ((fadeSource * (to - from)).toInt()).coerceAtLeast(1),
                                below.dstStart + fade * from,
                                fade * (to - from),
                            ),
                            alpha = middle,
                        )
                    }
                }
            }

            // Highlight of the selected band
            if (lines.size == 2 && layout.bandBottomScreen - layout.bandTopScreen > 1f) {
                val top = layout.bandTopScreen
                val bottom = layout.bandBottomScreen
                val colors = listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.12f), accent.copy(alpha = 0.26f))
                val topLeft = if (vertical) Offset(layout.crossStart, top) else Offset(top, layout.crossStart)
                val size = if (vertical) Size(layout.crossSize, bottom - top) else Size(bottom - top, layout.crossSize)
                drawRect(
                    brush = if (vertical) {
                        Brush.verticalGradient(colors, startY = top, endY = bottom)
                    } else {
                        Brush.horizontalGradient(colors, startX = top, endX = bottom)
                    },
                    topLeft = topLeft,
                    size = size,
                )
            }

            // Lines and their handles
            val frame = Color(0xFF06131A)
            lines.forEach { fraction ->
                val at = layout.fractionToScreen(fraction)
                val from = if (vertical) {
                    Offset(layout.crossStart - overhang, at)
                } else {
                    Offset(at, layout.crossStart - overhang)
                }
                val to = if (vertical) {
                    Offset(layout.crossStart + layout.crossSize + overhang, at)
                } else {
                    Offset(at, layout.crossStart + layout.crossSize + overhang)
                }
                drawLine(accent, from, to, strokeWidth = lineWidth, cap = StrokeCap.Round)
                val handle = layout.handleAt(fraction)
                drawCircle(frame.copy(alpha = 0.55f), radius = handleRadius + 3.dp.toPx(), center = handle)
                drawCircle(accent, radius = handleRadius, center = handle)
                drawCircle(frame, radius = handleRadius * 0.32f, center = handle)
            }
        }
    }
}
