package com.photostretcher.app.engine

import com.photostretcher.app.model.Axis
import com.photostretcher.app.model.StretchOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Tests for the stretch rule itself.
 *
 * These are plain JVM tests: [StretchMath] and [StretchOp] deliberately touch no Android API,
 * so the maths that decides what the saved picture looks like can be checked without a device.
 *
 * The check that matters most is [sampledSourceRows], which walks a [StretchLayout] the way
 * `Canvas.drawBitmap` would and returns, for every output row, the source row it samples. A
 * layout that overlaps or leaves a gap fails immediately, and the values then show whether the
 * band was resampled while the rest was left alone.
 */
class StretchMathTest {

    private val height = 1920
    private val width = 1080

    // ------------------------------------------------------------------ helpers

    /** Source row sampled by each output row, or a failure if the layout overlaps or has a gap. */
    private fun sampledSourceRows(layout: StretchLayout): FloatArray {
        val out = FloatArray(layout.length) { Float.NaN }
        for (p in layout.pieces) {
            // drawBitmap fills every row its float rect touches, so round both edges up.
            val from = ceil(p.dstStart).toInt()
            val to = ceil(p.dstEnd).toInt().coerceAtMost(layout.length)
            for (d in from until to) {
                assertTrue("row $d is covered by two pieces", out[d].isNaN())
                val t = if (p.dstSize == 0f) 0f else (d - p.dstStart) / p.dstSize
                out[d] = p.srcStart + t * p.srcSize
            }
        }
        for (d in out.indices) {
            assertTrue("row $d was never drawn, the layout has a gap", !out[d].isNaN())
        }
        return out
    }

    private fun assertCoversSourceInOrder(rows: FloatArray, baseLength: Int) {
        for (d in 1 until rows.size) {
            assertTrue("row $d goes backwards, from ${rows[d - 1]} to ${rows[d]}", rows[d] >= rows[d - 1] - 1e-3f)
        }
        assertTrue("starts at ${rows.first()}, not the first source row", abs(rows.first()) <= 1.5f)
        assertTrue(
            "ends at ${rows.last()}, not the last source row",
            abs(rows.last() - (baseLength - 1)) <= 1.5f,
        )
    }

    /** Asserts that output rows [from, to) are a straight 1:1 copy, i.e. not resampled at all. */
    private fun assertUntouched(rows: FloatArray, from: Int, to: Int) {
        if (to <= from) return
        val start = rows[from]
        for (d in from until to) {
            assertTrue("row $d drifted to ${rows[d]}, expected ${start + (d - from)}", abs(rows[d] - (start + (d - from))) <= 1.01f)
        }
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun `output size is A plus B times factor plus C`() {
        val op = StretchOp(Axis.VERTICAL, 0.40f, 0.60f, 2.5f)
        val (w, h) = StretchMath.outputSize(width, height, listOf(op))
        assertEquals("a vertical stretch must not change the width", width, w)
        assertEquals(height - 384f + 384f * 2.5f, h.toFloat(), 1f)
    }

    @Test
    fun `factor of one changes nothing at all`() {
        val noop = StretchOp(Axis.VERTICAL, 0.3f, 0.6f, 1f)
        assertTrue(noop.isNoOp)
        val layout = StretchMath.layout(height, listOf(noop), Axis.VERTICAL)
        assertEquals(height, layout.length)
        assertEquals(1, layout.pieces.size)
    }

    @Test
    fun `one stretch folds into exactly three cuts`() {
        val layout = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0.40f, 0.60f, 2.5f)), Axis.VERTICAL)
        assertEquals("a single stretch needs a single cut per part", 3, layout.pieces.size)

        val (a, b, c) = layout.pieces
        assertEquals("part A is a 1:1 blit", a.srcSize, a.dstSize, 0.5f)
        assertEquals("part A starts at the top", 0f, a.srcStart, 0.5f)
        assertEquals("part A ends at 40%", 0.40f * height, a.srcEnd, 1f)

        assertEquals("the band is stretched 2.5x", 2.5f, b.dstSize / b.srcSize, 0.01f)
        assertEquals(0.40f * height, b.srcStart, 1f)
        assertEquals(0.60f * height, b.srcEnd, 1f)

        assertEquals("part C is a 1:1 blit", c.srcSize, c.dstSize, 0.5f)
        assertEquals("part C starts at 60%", 0.60f * height, c.srcStart, 1f)
        assertEquals("part C runs to the last row", height.toFloat(), c.srcEnd, 1f)
    }

    @Test
    fun `the band is resampled and nothing else is`() {
        val op = StretchOp(Axis.VERTICAL, 0.40f, 0.60f, 2.5f)
        val layout = StretchMath.layout(height, listOf(op), Axis.VERTICAL)
        val rows = sampledSourceRows(layout)

        assertCoversSourceInOrder(rows, height)
        assertUntouched(rows, 0, 768)
        assertUntouched(rows, 1728, layout.length)

        val band = rows.copyOfRange(768, 1728)
        val fractional = band.count { abs(it - Math.round(it)) > 0.01f }
        assertTrue("the band must be interpolated, got $fractional whole rows", fractional in 100..900)
        assertEquals("the band still covers 384 source rows", 384f, band.last() - band.first(), 2f)
    }

    @Test
    fun `squash to a quarter makes the band a quarter as tall`() {
        val layout = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0.25f, 0.75f, 0.25f)), Axis.VERTICAL)
        assertTrue("squashing must shrink the picture", layout.length < height)
        assertEquals(0.25f, layout.pieces[1].dstSize / layout.pieces[1].srcSize, 0.01f)
        val rows = sampledSourceRows(layout)
        assertCoversSourceInOrder(rows, height)
        assertUntouched(rows, 0, 480)
        assertUntouched(rows, 720, layout.length)
    }

    @Test
    fun `a single pass of several stretches matches doing them one after another`() {
        val two = listOf(
            StretchOp(Axis.VERTICAL, 0.30f, 0.50f, 2f),
            StretchOp(Axis.VERTICAL, 0.40f, 0.80f, 1.5f),
        )
        val folded = StretchMath.layout(height, two, Axis.VERTICAL)

        val afterFirst = StretchMath.outputSize(width, height, listOf(two[0]))
        val sequential = StretchMath.outputSize(afterFirst.first, afterFirst.second, listOf(two[1]))

        assertEquals("the folded length must equal the sequential length", sequential.second, folded.length)
        assertTrue(StretchMath.isSingleAxis(two))
        assertCoversSourceInOrder(sampledSourceRows(folded), height)
    }

    @Test
    fun `every band position and factor behaves`() {
        val factors = listOf(0.25f, 0.5f, 1f, 1.7f, 3f, 4f)
        var checked = 0
        for (b0i in 0..8) {
            for (b1i in b0i + 1..9) {
                for (f in factors) {
                    val s = b0i / 10f
                    val e = b1i / 10f
                    checked++
                    val label = "band $s..$e at ${f}x"

                    val band = height * (e - s)
                    val a = height * s
                    val layout = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, s, e, f)), Axis.VERTICAL)
                    assertEquals("$label: length", height - band + band * f, layout.length.toFloat(), 1f)

                    val rows = sampledSourceRows(layout)
                    val bandEnd = a + band * f

                    assertUntouched(rows, 0, minOf(a.toInt(), layout.length))
                    assertUntouched(rows, ceil(bandEnd).toInt(), layout.length)
                    assertCoversSourceInOrder(rows, height)

                    if (band * f >= 2) {
                        // One source row covers 1/f output rows, so the last row inside the band
                        // samples a source row within 1/f of the band's last source row.
                        val last = ceil(bandEnd).toInt().coerceAtMost(layout.length) - 1
                        assertTrue("$label: band ends at ${rows[last]}, wanted ${e * height}", abs(rows[last] - e * height) <= 1f / f + 1f)
                    }
                }
            }
        }
        assertEquals(270, checked)
    }

    @Test
    fun `horizontal mode leaves the height alone`() {
        val op = StretchOp(Axis.HORIZONTAL, 0.25f, 0.75f, 2f)
        val (w, h) = StretchMath.outputSize(width, height, listOf(op))
        assertEquals("a horizontal stretch must not change the height", height, h)
        assertEquals(width - 540f + 1080f, w.toFloat(), 1f)

        val layout = StretchMath.layout(width, listOf(op), Axis.HORIZONTAL)
        assertEquals(w, layout.length)
        assertCoversSourceInOrder(sampledSourceRows(layout), width)
    }

    @Test
    fun `mixing both axes is detected`() {
        val v = StretchOp(Axis.VERTICAL, 0.4f, 0.6f, 2f)
        val h = StretchOp(Axis.HORIZONTAL, 0.4f, 0.6f, 2f)
        assertTrue(StretchMath.isSingleAxis(listOf(v)))
        assertTrue(StretchMath.isSingleAxis(listOf(v, h.copy(bandStart = 0.2f, bandEnd = 0.3f))))
        assertTrue(!StretchMath.isSingleAxis(listOf(v, h)))
    }

    @Test
    fun `scaledBy only shrinks the destination`() {
        val layout = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0.4f, 0.6f, 2.5f)), Axis.VERTICAL)
        val half = layout.scaledBy(0.5f)
        assertEquals(layout.length / 2, half.length)
        assertEquals("source offsets must be left alone", layout.pieces[0].srcStart, half.pieces[0].srcStart, 0.001f)
    }

    @Test
    fun `degenerate bands are refused rather than crashing`() {
        val zero = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0.5f, 0.5f, 3f)), Axis.VERTICAL)
        assertEquals("a zero height band is a finger accident", height, zero.length)

        val subPixel = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0.5f, 0.5001f, 4f)), Axis.VERTICAL)
        assertEquals("a sub pixel band is a finger accident", height, subPixel.length)

        val top = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0f, 0.5f, 4f)), Axis.VERTICAL)
        assertEquals("A = 0, B = 960 * 4, C = 960", 4800, top.length)
        assertCoversSourceInOrder(sampledSourceRows(top), height)

        val bottom = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0.5f, 1f, 4f)), Axis.VERTICAL)
        assertEquals("A = 960, B = 960 * 4, C = 0", 4800, bottom.length)
        assertCoversSourceInOrder(sampledSourceRows(bottom), height)

        val whole = StretchMath.layout(height, listOf(StretchOp(Axis.VERTICAL, 0f, 1f, 4f)), Axis.VERTICAL)
        assertEquals("the whole picture at 4x", 7680, whole.length)
    }
}
