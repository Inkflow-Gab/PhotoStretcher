package com.photostretcher.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.photostretcher.app.model.Axis
import com.photostretcher.app.model.StretchOp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the stretch described by [StretchMath] into a new bitmap.
 *
 * The source picture is never resampled as a whole: every straight cut found by
 * [StretchMath.layout] is drawn straight from the source bitmap into the result with
 * bilinear filtering, so the parts above and below the band are pixel for pixel the
 * original (a plain 1:1 blit, no filtering at all, when the scale is 1).
 */
object StretchRenderer {

    /** Width of the soft seam blend, as a fraction of the result, capped at [MAX_SEAM_PX]. */
    private const val SEAM_FRACTION = 1f / 300f
    private const val MAX_SEAM_PX = 8f

    /**
     * @param scale shrinks the whole result by this factor. The export uses it to squeeze a
     *        huge stretch into the memory the device actually has: the source pixels are
     *        untouched, only the size of the result changes.
     * @return a new bitmap, or [base] itself when [ops] would not change anything.
     *         The caller owns the result and must not recycle [base] while it is displayed.
     */
    fun render(
        base: Bitmap,
        ops: List<StretchOp>,
        smoothEdges: Boolean = false,
        scale: Float = 1f,
    ): Bitmap {
        val useful = ops.filter { !it.isNoOp }
        if (useful.isEmpty()) return base
        val factor = scale.coerceIn(0.01f, 1f)
        val axis = useful.first().axis
        // Mixing both axes is only reachable by flipping the mode mid edit, so the slower but
        // always correct path is good enough there.
        return if (StretchMath.isSingleAxis(useful)) {
            singlePass(base, useful, axis, smoothEdges, factor)
        } else {
            sequential(base, useful, smoothEdges, factor)
        }
    }

    private fun singlePass(
        base: Bitmap,
        ops: List<StretchOp>,
        axis: Axis,
        smoothEdges: Boolean,
        scale: Float,
    ): Bitmap {
        val vertical = axis == Axis.VERTICAL
        val layout = StretchMath
            .layout(if (vertical) base.height else base.width, ops, axis)
            .scaledBy(scale)
        val out = Bitmap.createBitmap(
            (base.width * scale).roundToInt().coerceAtLeast(1),
            if (vertical) layout.length else (base.height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        val paint = bitmapPaint()
        val src = Rect()
        val dst = RectF()

        for (piece in layout.pieces) {
            if (piece.isNegligible) continue
            if (vertical) {
                src.set(0, piece.srcStart.roundToInt(), base.width, piece.srcEnd.roundToInt())
                dst.set(0f, piece.dstStart, base.width.toFloat(), piece.dstEnd)
            } else {
                src.set(piece.srcStart.roundToInt(), 0, piece.srcEnd.roundToInt(), base.height)
                dst.set(piece.dstStart, 0f, piece.dstEnd, base.height.toFloat())
            }
            canvas.drawBitmap(base, src, dst, paint)
        }

        if (smoothEdges) blendSeams(canvas, base, layout, vertical)
        return out
    }

    /** Fallback for edits that mix both axes: stretch one operation at a time. */
    private fun sequential(base: Bitmap, ops: List<StretchOp>, smoothEdges: Boolean, scale: Float): Bitmap {
        var current = base
        ops.forEachIndexed { index, op ->
            val last = index == ops.lastIndex
            val next = singlePass(current, listOf(op), op.axis, smoothEdges && last, if (last) scale else 1f)
            if (current !== base) current.recycle()
            current = next
        }
        return current
    }

    private fun bitmapPaint() = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    /**
     * Softens the hard joins. Wherever the mapping was cut, the strip above and the strip
     * below are drawn again over a few pixels with opposite alpha ramps, which cross fades
     * the two textures instead of cutting between them.
     */
    private fun blendSeams(canvas: Canvas, base: Bitmap, layout: StretchLayout, vertical: Boolean) {
        val wanted = (layout.length * SEAM_FRACTION).coerceAtMost(MAX_SEAM_PX).coerceAtLeast(1f)
        val paint = bitmapPaint()
        val src = Rect()
        val dst = RectF()

        for (index in 1 until layout.pieces.size) {
            val above = layout.pieces[index - 1]
            val below = layout.pieces[index]
            if (kotlin.math.abs(above.srcEnd - below.srcStart) < 0.5f) continue // nothing was cut here

            val at = below.dstStart
            val fadeOut = min(wanted, min(at - above.dstStart, above.dstEnd - at))
            val fadeIn = min(wanted, min(at - below.dstStart, below.dstEnd - at))
            if (fadeOut < 1f || fadeIn < 1f) continue

            if (vertical) {
                // strip above: opaque at the far end of the fade, gone at the seam
                src.set(0, (above.srcEnd - fadeOut).roundToInt(), base.width, above.srcEnd.roundToInt())
                dst.set(0f, at - fadeOut, base.width.toFloat(), at)
                paint.shader = LinearGradient(
                    0f, at - fadeOut, 0f, at,
                    intArrayOf(Color.BLACK, Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
                )
                canvas.drawBitmap(base, src, dst, paint)

                // strip below: the mirror image
                src.set(0, below.srcStart.roundToInt(), base.width, (below.srcStart + fadeIn).roundToInt())
                dst.set(0f, at, base.width.toFloat(), at + fadeIn)
                paint.shader = LinearGradient(
                    0f, at, 0f, at + fadeIn,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK), null, Shader.TileMode.CLAMP,
                )
                canvas.drawBitmap(base, src, dst, paint)
            } else {
                src.set((above.srcEnd - fadeOut).roundToInt(), 0, above.srcEnd.roundToInt(), base.height)
                dst.set(at - fadeOut, 0f, at, base.height.toFloat())
                paint.shader = LinearGradient(
                    at - fadeOut, 0f, at, 0f,
                    intArrayOf(Color.BLACK, Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
                )
                canvas.drawBitmap(base, src, dst, paint)

                src.set(below.srcStart.roundToInt(), 0, (below.srcStart + fadeIn).roundToInt(), base.height)
                dst.set(at, 0f, at + fadeIn, base.height.toFloat())
                paint.shader = LinearGradient(
                    at, 0f, at + fadeIn, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK), null, Shader.TileMode.CLAMP,
                )
                canvas.drawBitmap(base, src, dst, paint)
            }
        }
        paint.shader = null
    }
}
