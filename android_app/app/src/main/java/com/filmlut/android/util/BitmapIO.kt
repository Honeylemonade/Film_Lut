package com.filmlut.android.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object BitmapIO {
    fun decodeBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        check(sourceWidth > 0 && sourceHeight > 0) { "Unable to decode image bounds" }

        val sampleSize = calculateInSampleSize(sourceWidth, sourceHeight, maxEdge)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val bitmap = resolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("Unable to decode image bitmap")

        val orientation = resolver.openInputStream(uri).use { stream ->
            if (stream == null) {
                ExifInterface.ORIENTATION_UNDEFINED
            } else {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            }
        }
        return rotateIfNeeded(bitmap, orientation)
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, lutName: String): Uri {
        val resolver = context.contentResolver
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val displayName = "FilmLut_${formatter.format(Date())}_${lutName.sanitizeFileName()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FilmLut")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val uri = resolver.insert(collection, values) ?: error("Unable to create media store record")
        resolver.openOutputStream(uri).use { output ->
            check(output != null) { "Unable to open output stream" }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index) ?: "Photo"
                }
            }
            "Photo"
        }.getOrDefault("Photo")
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sampleSize = 1
        val longestEdge = max(width, height)
        while (longestEdge / sampleSize > maxEdge) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun rotateIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun String.sanitizeFileName(): String {
        return replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5._-]+"), "_").trim('_')
    }
}
