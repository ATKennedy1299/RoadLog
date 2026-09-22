package com.roadlog.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Vehicle photos are copied out of the picker's content:// Uri into this
 * app's private files dir immediately on selection, downsampled and
 * re-encoded as JPEG — a picker grant isn't guaranteed to survive process
 * death or a reboot, and a raw phone-camera photo is far bigger than this
 * app ever needs to display.
 */
class VehiclePhotoStore(private val context: Context) {

    private val photosDir: File
        get() = File(context.filesDir, "vehicle_photos").apply { mkdirs() }

    suspend fun savePhoto(sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeSampledBitmap(sourceUri, maxDimension = 1600)
        val file = File(photosDir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        bitmap.recycle()
        file.absolutePath
    }

    suspend fun deletePhoto(path: String?) = withContext(Dispatchers.IO) {
        if (path != null) File(path).takeIf { it.exists() }?.delete()
        Unit
    }

    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension ||
            bounds.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Could not decode selected image")
    }
}
