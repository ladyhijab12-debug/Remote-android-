package com.daftar.kredi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Invoice photos, shrunk to a readable size and kept in the app's private storage. */
object PhotoStore {

    private const val MAX_SIDE = 1600

    fun dir(context: Context): File = File(context.filesDir, "invoices").apply { mkdirs() }

    fun file(context: Context, name: String): File? =
        if (name.isNotEmpty() && PhotoName.isValid(name)) File(dir(context), name).takeIf { it.isFile } else null

    /** Copies the picture at [uri] (camera or gallery) into the store; returns its new name. */
    fun import(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bitmap = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("not an image")

        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        val rotation = runCatching {
            resolver.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (!matrix.isIdentity) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val name = PhotoName.new()
        File(dir(context), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return name
    }

    /** A small version for previews in lists and dialogs. */
    fun thumbnail(context: Context, name: String, side: Int): Bitmap? {
        val f = file(context, name) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun delete(context: Context, name: String) {
        file(context, name)?.delete()
    }

    /** Removes photos no entry points to any more (after edits, deletes or a restore). */
    fun cleanup(context: Context, used: Set<String>) {
        dir(context).listFiles()?.forEach { if (it.name !in used) it.delete() }
    }
}
