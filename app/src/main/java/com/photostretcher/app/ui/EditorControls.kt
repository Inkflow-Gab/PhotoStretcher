package com.photostretcher.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.photostretcher.app.engine.SaveFormat
import com.photostretcher.app.model.Axis
import com.photostretcher.app.model.StretchOp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the editor screen lets you change while a photo is open.
 *
 * Line positions are fractions of the picture (0f..1f), never pixels, and they are always
 * sorted: [lines][0] is the top (or left) line, [lines][1] is the bottom (or right) one, no
 * matter which one the finger put there first.
 */
class EditorControls {

    /** 0, 1 or 2 lines. Two lines means a band is selected. */
    var lines by mutableStateOf(emptyList<Float>())
        private set

    /** The stretch strength in percent, see [stretchRange]. */
    var percent by mutableStateOf(100)
        private set

    var axis by mutableStateOf(Axis.VERTICAL)
        private set

    /** `true` when the slider goes below 100% and squashes the band instead. */
    var squash by mutableStateOf(false)
        private set

    var smoothEdges by mutableStateOf(false)

    var format by mutableStateOf(SaveFormat.JPEG)

    /** `true` once two lines are placed, which is when stretching becomes possible. */
    val hasBand: Boolean get() = lines.size == 2

    val factor: Float get() = percent / 100f

    /** Slider bounds in percent, for the [androidx.compose.material3.Slider]. */
    val stretchRange: ClosedFloatingPointRange<Float>
        get() = if (squash) SQUASH_RANGE else STRETCH_RANGE

    /** Slider bounds as whole percent, used to clamp what the slider hands us. */
    val percentMin: Int get() = if (squash) SQUASH_MIN else STRETCH_MIN

    val percentMax: Int get() = if (squash) SQUASH_MAX else STRETCH_MAX

    /** `true` when there is something to save. */
    val canSave: Boolean get() = hasBand && percent != 100

    /** `true` when leaving the editor would throw work away. */
    val isDirty: Boolean get() = lines.isNotEmpty() || percent != 100

    /** The stretch the user is working on right now, if any. */
    fun pendingOp(): StretchOp? =
        if (canSave) StretchOp(axis, lines[0], lines[1], factor) else null

    // ---------------------------------------------------------------- gestures

    fun onTap(fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        lines = when {
            lines.isEmpty() -> listOf(value)
            lines.size == 1 -> listOf(min(lines[0], value), max(lines[0], value))
            else -> {
                // Third tap and onwards: move whichever line is closer to the finger.
                val nearest = if (abs(value - lines[0]) <= abs(value - lines[1])) 0 else 1
                lines.toMutableList().apply { set(nearest, value) }.sorted()
            }
        }
    }

    fun onDragLine(index: Int, fraction: Float) {
        if (index !in lines.indices) return
        val other = lines.getOrNull(1 - index)
        val value = if (other == null) {
            fraction.coerceIn(0f, 1f)
        } else {
            // Lines may never cross, so the dragged one stops at the minimum gap.
            fraction.coerceIn(other - MIN_GAP, other + MIN_GAP)
        }
        lines = lines.toMutableList().apply { set(index, value) }
    }

    fun onDragBand(delta: Float) {
        if (lines.size < 2) return
        val shift = delta.coerceIn(-lines[0], 1f - lines[1])
        lines = listOf(lines[0] + shift, lines[1] + shift)
    }

    // ----------------------------------------------------------------- actions

    fun setPercent(value: Float) {
        percent = value.roundToInt().coerceIn(percentMin, percentMax)
    }

    fun resetLines() {
        lines = emptyList()
        percent = 100
    }

    fun afterApply() {
        lines = emptyList()
        percent = 100
    }

    fun toggleAxis() {
        axis = if (axis == Axis.VERTICAL) Axis.HORIZONTAL else Axis.VERTICAL
        lines = emptyList()
        percent = 100
    }

    fun toggleSquash() {
        squash = !squash
        percent = if (squash) 75 else 100
    }

    fun toggleSmooth() {
        smoothEdges = !smoothEdges
    }

    fun toggleFormat() {
        format = if (format == SaveFormat.PNG) SaveFormat.JPEG else SaveFormat.PNG
    }

    companion object {
        /** Smallest gap between the two lines, as a fraction of the picture. */
        const val MIN_GAP = 0.025f

        const val STRETCH_MIN = 100
        const val STRETCH_MAX = 400
        const val SQUASH_MIN = 25
        const val SQUASH_MAX = 100

        val STRETCH_RANGE = STRETCH_MIN.toFloat()..STRETCH_MAX.toFloat()
        val SQUASH_RANGE = SQUASH_MIN.toFloat()..SQUASH_MAX.toFloat()
    }
}
