package com.photostretcher.app.engine

import com.photostretcher.app.model.Axis
import com.photostretcher.app.model.StretchOp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One straight cut through the picture: the source strip between
 * [srcStart] and [srcEnd] ends up between [dstStart] and [dstEnd] in the result.
 * All offsets are pixels measured along the stretched axis.
 */
data class Piece(
    val srcStart: Float,
    val srcEnd: Float,
    val dstStart: Float,
    val dstEnd: Float,
) {
    val srcSize: Float get() = srcEnd - srcStart
    val dstSize: Float get() = dstEnd - dstStart

    /** `true` when the strip is too small to be worth drawing. */
    val isNegligible: Boolean get() = abs(srcSize) < 0.5f || abs(dstSize) < 0.5f
}

/** The folded result of a list of [StretchOp]s: where every strip of the source ends up. */
data class StretchLayout(
    /** Length of the result along the stretched axis, in pixels. */
    val length: Int,
    /** Source strips in drawing order. [pieces][0] starts at 0 and the last one ends at [length]. */
    val pieces: List<Piece>,
) {
    /** The same cuts, drawn [factor] times smaller. Source offsets are left alone on purpose. */
    fun scaledBy(factor: Float): StretchLayout {
        if (factor == 1f) return this
        val f = factor.coerceIn(0.01f, 1f)
        return StretchLayout(
            length = max(1, (length * f).roundToInt()),
            pieces = pieces.map { it.copy(dstStart = it.dstStart * f, dstEnd = it.dstEnd * f) },
        )
    }
}

/**
 * The stretch math, all of it, in one small file.
 *
 * ## One operation
 *
 * Take an image of length `L` (its height for a vertical stretch, its width for a
 * horizontal one). A band from `b0 = bandStart * L` to `b1 = bandEnd * L` is scaled by
 * `f = factor` along that axis. Every position `d` of the old image moves to
 *
 * ```
 *     d <= b0  ->  d                                  (part A: untouched)
 *     d <= b1  ->  b0 + (d - b0) * f                 (part B: stretched)
 *     d >= b1  ->  d + (b1 - b0) * (f - 1)           (part C: slides down / right)
 * ```
 *
 * Part C only *translates*, it never scales, which is exactly the "only the band changes"
 * behaviour we want: the pixels above and below keep their size and their content.
 *
 * ## Many operations
 *
 * The mapping above is continuous and piecewise linear, so applying a second operation to
 * the result of the first one just means re-applying the same mapping to the *destination*
 * offsets. Each cut therefore gets split at the new band edges, and the whole list of
 * operations collapses into a flat list of [Piece]s that can be drawn with one
 * `drawBitmap` call each - no intermediate bitmaps, no drift between preview and export.
 */
object StretchMath {

    /** Bands thinner than this many pixels are ignored (they are a finger accident, not a request). */
    private const val MIN_BAND_PIXELS = 1f

    /** `true` when every operation stretches the same axis, which allows the single pass path. */
    fun isSingleAxis(ops: List<StretchOp>): Boolean {
        val axis = ops.firstOrNull { !it.isNoOp }?.axis ?: return true
        return ops.none { !it.isNoOp && it.axis != axis }
    }

    /**
     * Folds [ops] (in order) into a flat list of straight cuts.
     *
     * @param baseLength length of the source image along [axis], in pixels
     */
    fun layout(baseLength: Int, ops: List<StretchOp>, axis: Axis): StretchLayout {
        val base = baseLength.toFloat().coerceAtLeast(1f)
        var pieces = listOf(Piece(0f, base, 0f, base))
        var length = base

        for (op in ops) {
            if (op.isNoOp || op.axis != axis) continue
            // A band thinner than a pixel is a finger accident, not a request: skip it and
            // keep every stretch that was applied before it.
            val applied = apply(pieces, length, op) ?: continue
            pieces = applied.first
            length = applied.second
        }
        return StretchLayout(max(1, length.roundToInt()), pieces)
    }

    /**
     * Applies a single [op] on top of an existing mapping.
     * Returns the new pieces plus the new length, or `null` when the band is too thin.
     */
    private fun apply(pieces: List<Piece>, length: Float, op: StretchOp): Pair<List<Piece>, Float>? {
        val b0 = op.bandStart * length
        val b1 = op.bandEnd * length
        if (b1 - b0 < MIN_BAND_PIXELS) return null
        val f = op.factor
        if (f <= 0f) return null

        val stretchedBand = (b1 - b0) * f
        val tailShift = stretchedBand - (b1 - b0) // how far part C moves

        // old position -> new position, and back again
        fun toDest(d: Float): Float = when {
            d <= b0 -> d
            d >= b1 -> d + tailShift
            else -> b0 + (d - b0) * f
        }

        fun toSrc(d: Float): Float = when {
            d <= b0 -> d
            d <= b0 + stretchedBand -> b0 + (d - b0) / f
            else -> d - tailShift
        }

        val next = ArrayList<Piece>(pieces.size + 2)
        for (piece in pieces) {
            val span = piece.dstEnd - piece.dstStart
            val slope = if (span > 0f) (piece.srcEnd - piece.srcStart) / span else 0f

            // Everything below is in *destination* space: this op maps the current image onto
            // the new one, so a piece's new-image extent is toDest of its current-image extent.
            val newFrom = toDest(piece.dstStart)
            val newTo = toDest(piece.dstEnd)

            // Source position of a destination position: the piece is a straight cut, so it
            // is a plain linear walk along it.
            fun srcAt(d: Float): Float = piece.srcStart + (toSrc(d) - piece.dstStart) * slope

            // The two places where the slope changes, in destination space: the top of the band
            // and the bottom of the stretched band. b1 is a *current image* position and must
            // not be used here, it belongs to the other end of the same bend. A cut that spans
            // one of these has to be split, otherwise one straight cut cannot represent it.
            val bends = listOf(b0, b0 + stretchedBand)
                .filter { it > newFrom && it < newTo }
                .distinct()
                .sorted()

            var from = newFrom
            for (bend in bends) {
                next += Piece(srcAt(from), srcAt(bend), from, bend)
                from = bend
            }
            // srcAt(newFrom) is the piece's own srcStart and srcAt(newTo) its srcEnd, so the
            // ends of the piece carry over unchanged.
            next += Piece(srcAt(from), srcAt(newTo), from, newTo)
        }
        return next to toDest(length)
    }

    /**
     * Final size of the picture after applying [ops] in order, without allocating anything.
     * Used to work out how much memory the export will need.
     */
    fun outputSize(width: Int, height: Int, ops: List<StretchOp>): Pair<Int, Int> {
        var w = width.toFloat()
        var h = height.toFloat()
        for (op in ops) {
            if (op.isNoOp) continue
            if (op.axis == Axis.VERTICAL) {
                val band = h * op.bandSize
                h = h - band + band * op.factor
            } else {
                val band = w * op.bandSize
                w = w - band + band * op.factor
            }
        }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }
}
