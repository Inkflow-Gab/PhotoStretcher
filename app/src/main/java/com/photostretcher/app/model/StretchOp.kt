package com.photostretcher.app.model

/** The direction a band gets stretched in. */
enum class Axis {
    /** Lines are horizontal, the band gets taller / shorter. */
    VERTICAL,

    /** Lines are vertical, the band gets wider / narrower. */
    HORIZONTAL,
}

/**
 * One stretch operation.
 *
 * [bandStart] and [bandEnd] are fractions (0f..1f) of the image length **along [axis]**,
 * measured on the image as it looked *before* this operation was applied.
 *
 * Storing fractions instead of pixels is what makes the on screen preview and the
 * full resolution export produce exactly the same picture: the same numbers are simply
 * multiplied by the preview size or by the real image size.
 *
 * [factor] is the new length of the band divided by its original length:
 * `2f` makes the band twice as long, `0.5f` makes it half as long.
 */
data class StretchOp(
    val axis: Axis = Axis.VERTICAL,
    val bandStart: Float,
    val bandEnd: Float,
    val factor: Float,
) {
    init {
        require(bandStart in 0f..1f) { "bandStart out of range: $bandStart" }
        require(bandEnd in 0f..1f) { "bandEnd out of range: $bandEnd" }
        require(bandEnd >= bandStart) { "bandEnd ($bandEnd) must not be below bandStart ($bandStart)" }
        require(factor > 0f) { "factor must be positive: $factor" }
    }

    /** Size of the band as a fraction of the image, before stretching. */
    val bandSize: Float get() = bandEnd - bandStart

    /** `true` when this operation would not change anything. */
    val isNoOp: Boolean get() = factor == 1f || bandSize <= 0f

    companion object {
        const val MIN_FACTOR = 0.25f
        const val MAX_FACTOR = 4f

        fun clamped(factor: Float): Float = factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }
}
