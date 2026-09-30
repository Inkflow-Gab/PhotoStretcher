package com.photostretcher.app.engine

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The two file types the export can write. */
enum class SaveFormat(val extension: String, val mimeType: String, val quality: Int) {
    JPEG("jpg", "image/jpeg", 95),
    PNG("png", "image/png", 100),
}

/** Where the picture ended up. */
data class SavedImage(
    /** The MediaStore entry (or file) the picture was written to. */
    val uri: Uri,
    /** A uri that can be handed to another app. */
    val shareUri: Uri,
    val width: Int,
    val height: Int,
)

/**
 * Writes the result into the gallery, into `Pictures/PhotoStretcher`.
 *
 * The original file is never touched: the result always gets a brand new file called
 * `photostretcher_YYYYMMDD_HHMMSS.jpg`.
 */
object ImageSaver {

    /** Folder created inside the system Pictures folder. */
    const val FOLDER = "PhotoStretcher"

    /** Only Android 8 and 9 need this to write into the gallery. */
    fun needsLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun legacyWritePermission(): String = Manifest.permission.WRITE_EXTERNAL_STORAGE

    fun hasLegacyWritePermission(context: Context): Boolean =
        !needsLegacyWritePermission() ||
            ContextCompat.checkSelfPermission(context, legacyWritePermission()) == PackageManager.PERMISSION_GRANTED

    fun buildName(format: SaveFormat, millis: Long = System.currentTimeMillis()): String =
        "photostretcher_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis)) +
            "." + format.extension

    /** Writes [bitmap] and returns where it landed. */
    fun save(context: Context, bitmap: Bitmap, format: SaveFormat, name: String = buildName(format)): SavedImage {
        val location = if (needsLegacyWritePermission()) {
            saveToPublicFolder(context, bitmap, format, name)
        } else {
            saveToMediaStore(context, bitmap, format, name)
        }
        return SavedImage(
            uri = location.uri,
            shareUri = location.shareUri,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    private data class Location(val uri: Uri, val shareUri: Uri)

    private fun saveToMediaStore(context: Context, bitmap: Bitmap, format: SaveFormat, name: String): Location {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("The gallery did not accept the new picture.")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.writeTo(format, format.quality, output)) {
                    throw IOException("The picture could not be written.")
                }
            } ?: throw IOException("The picture could not be written.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            // Do not leave a half written entry behind in the gallery.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        return Location(uri, uri)
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicFolder(context: Context, bitmap: Bitmap, format: SaveFormat, name: String): Location {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER,
        )
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("The $FOLDER folder could not be created.")
        }
        var target = File(folder, name)
        var bump = 1_000L // two saves inside the same second must not overwrite each other
        while (target.exists()) {
            target = File(folder, buildName(format, System.currentTimeMillis() + bump))
            bump += 1_000L
        }
        FileOutputStream(target).use { output ->
            if (!bitmap.writeTo(format, format.quality, output)) {
                throw IOException("The picture could not be written.")
            }
        }
        // Let the gallery notice the new file.
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(format.mimeType), null)
        val share = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return Location(Uri.fromFile(target), share)
    }

    private fun Bitmap.writeTo(format: SaveFormat, quality: Int, output: java.io.OutputStream): Boolean =
        compress(
            if (format == SaveFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            quality,
            output,
        )
}
