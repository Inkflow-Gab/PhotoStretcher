package com.photostretcher.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo that has been read, already turned the right way up. */
data class LoadedPhoto(
    /** Small copy for the editor. */
    val bitmap: Bitmap,
    /** Size of the *original* file once the EXIF rotation has been applied. */
    val fullWidth: Int,
    val fullHeight: Int,
)

/**
 * Reads photos without ever blowing up on a big one.
 *
 * The editor only ever draws a picture that is at most [PREVIEW_MAX_EDGE] pixels on its long
 * side, so even a 50 megapixel phone photo costs a few megabytes while editing. The full
 * resolution copy is read only when the user saves, and even then it is capped so that the
 * result still fits in memory.
 */
object ImageDecoder {

    /** Long side of the bitmap used for the on screen preview. */
    const val PREVIEW_MAX_EDGE = 1400

    /** Reads a small copy for the editor, turned the right way up. */
    fun decodePreview(context: Context, uri: Uri): LoadedPhoto {
        val (rawWidth, rawHeight) = readBounds(context, uri)
        if (rawWidth <= 0 || rawHeight <= 0) throw IOException(NOT_AN_IMAGE)
        val rotation = readRotation(context, uri)
        val upright = if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight

        val sample = sampleSize(max(rawWidth, rawHeight), PREVIEW_MAX_EDGE)
        val decoded = decode(context, uri, sample) ?: throw IOException(NOT_AN_IMAGE)
        val preview = fitWithin(applyRotation(decoded, rotation), PREVIEW_MAX_EDGE)
        return LoadedPhoto(preview, upright.first, upright.second)
    }

    /**
     * Reads a copy whose long side is about [maxEdge] pixels, turned the right way up.
     * The export uses this, so it never reads a file bigger than it can hold.
     */
    fun decodeForExport(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val (rawWidth, rawHeight) = readBounds(context, uri)
        if (rawWidth <= 0 || rawHeight <= 0) throw IOException(NOT_AN_IMAGE)
        val rotation = readRotation(context, uri)
        val sample = sampleSize(max(rawWidth, rawHeight), maxEdge.coerceIn(1, MemoryPlan.MAX_EDGE))
        val decoded = decode(context, uri, sample) ?: throw IOException(NOT_AN_IMAGE)
        return applyRotation(decoded, rotation)
    }

    /** Longest and shortest side of the original photo, after the EXIF rotation. */
    fun readFullSize(context: Context, uri: Uri): Pair<Int, Int> {
        val (rawWidth, rawHeight) = readBounds(context, uri)
        if (rawWidth <= 0 || rawHeight <= 0) throw IOException(NOT_AN_IMAGE)
        val rotation = readRotation(context, uri)
        return if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight
    }

    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth to options.outHeight
    }

    /** Decodes with [BitmapFactory.inSampleSize], halving again if memory runs out. */
    private fun decode(context: Context, uri: Uri, sample: Int): Bitmap? {
        var attempt = sample
        while (true) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = attempt
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = try {
                openStream(context, uri).use { BitmapFactory.decodeStream(it, null, options) }
            } catch (error: OutOfMemoryError) {
                null
            }
            if (decoded != null) return decoded
            if (attempt >= 32) return null
            attempt *= 2
        }
    }

    private fun openStream(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: throw IOException(NOT_AN_IMAGE)

    /** Largest power of two that still leaves the picture at or above [wanted] pixels. */
    private fun sampleSize(longEdge: Int, wanted: Int): Int {
        var sample = 1
        while (longEdge / (sample * 2) >= wanted) sample *= 2
        return sample
    }

    /** Scales [bitmap] down so that its long side is at most [maxEdge]. */
    private fun fitWithin(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val factor = maxEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).roundToInt().coerceAtLeast(1),
            (bitmap.height * factor).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun readRotation(context: Context, uri: Uri): Int = runCatching {
        val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching 0
        stream.use { input ->
            val orientation = ExifInterface(input)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    }.getOrDefault(0)

    private fun applyRotation(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private const val NOT_AN_IMAGE = "That file could not be read as an image."
}
