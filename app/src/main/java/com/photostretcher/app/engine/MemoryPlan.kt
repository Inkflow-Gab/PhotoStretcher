package com.photostretcher.app.engine

import com.photostretcher.app.model.StretchOp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Keeps the export inside the memory the device actually has.
 *
 * A 12 megapixel photo is already ~48 MB as a bitmap. Stretching half of it by 4x turns that
 * into ~144 MB, which plenty of phones simply cannot hold. So before rendering at full
 * resolution we work out how far the result has to be shrunk, read the source at a matching
 * size, and let the renderer produce a result of exactly that size.
 */
object MemoryPlan {

    /** Four bytes per pixel (ARGB_8888). */
    private const val BYTES_PER_PIXEL = 4L

    /** Never produce anything longer than this on one side. */
    const val MAX_EDGE = 12000

    /** Even on a roomy device the export stays under this. */
    private const val MAX_BUDGET_BYTES = 192L * 1024 * 1024

    /** Reading less than this would look silly, so tiny results still get a decent source. */
    private const val MIN_SOURCE_EDGE = 480

    /** Pixels the *result* may use. A third of the heap leaves room for the source bitmap. */
    fun budgetPixels(): Long {
        val heap = Runtime.getRuntime().maxMemory()
        return min(heap / 3, MAX_BUDGET_BYTES) / BYTES_PER_PIXEL
    }

    /**
     * How far a [width] x [height] result has to be shrunk to fit in memory.
     * Returns 1f when it fits as is.
     */
    fun scaleFor(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        val pixels = width.toLong() * height.toLong()
        val byMemory = sqrt(budgetPixels().toDouble() / pixels.toDouble()).toFloat()
        val byEdge = min(1f, MAX_EDGE.toFloat() / max(width, height))
        return min(1f, min(byMemory, byEdge)).coerceIn(0.02f, 1f)
    }

    /**
     * Longest side worth reading from the file. The source never needs more detail than the
     * result has pixels, so huge photos are not decoded for nothing.
     */
    fun sourceLongEdge(baseWidth: Int, baseHeight: Int, ops: List<StretchOp>, scale: Float): Int {
        val (outWidth, outHeight) = StretchMath.outputSize(baseWidth, baseHeight, ops)
        val outLong = max(outWidth, outHeight)
        val baseLong = max(baseWidth, baseHeight)
        val shortSide = min(baseWidth, baseHeight).coerceAtLeast(1)
        // Long side of a picture with the same shape that uses the whole pixel budget.
        val pixelCap = sqrt(budgetPixels() * (baseLong.toDouble() / shortSide)).toFloat()
        val wanted = min(outLong * scale, pixelCap)
        return wanted.roundToInt().coerceIn(MIN_SOURCE_EDGE, MAX_EDGE)
    }
}
