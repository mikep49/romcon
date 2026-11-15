package dev.romcon.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pictures chosen as icons for custom shortcuts. Each is a copy, scaled to a small square PNG in app-private
 * storage, so it keeps working if the original is deleted and costs little to draw.
 */
object ShortcutImages {
    /** Big enough to stay sharp at the editor's preview size on the densest screens. */
    private const val SIZE = 192

    /** Large photos are decoded at roughly this size, not in full, to save memory. */
    private const val DECODE_MAX = 768

    private fun dir(context: Context) = File(context.filesDir, "shortcut-icons")

    /** Names this class gives its files; anything else read from settings is ignored. */
    fun isValidName(name: String): Boolean = name.endsWith(".png") && name.all { it.isLetterOrDigit() || it == '-' || it == '.' }

    /** Copies the picture at [uri] in as an icon for the shortcut [id] and returns the new file's name. */
    fun import(context: Context, uri: Uri, id: String): String {
        val source = decode(context, uri) ?: throw IOException("Not a picture")
        val icon = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        // Fit the whole picture in the square: logos keep their edges, and the rest stays transparent.
        val scale = min(SIZE.toFloat() / source.width, SIZE.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        Canvas(icon).drawBitmap(source, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        source.recycle()

        val name = "$id-${UUID.randomUUID().toString().take(8)}.png"
        val dir = dir(context)
        dir.mkdirs()
        val file = File(dir, name)
        try {
            file.outputStream().use { if (!icon.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Couldn't save the picture") }
        } catch (e: IOException) {
            file.delete()
            throw e
        } finally {
            icon.recycle()
        }
        return name
    }

    fun load(context: Context, name: String): Bitmap? {
        if (!isValidName(name)) return null
        val file = File(dir(context), name)
        return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    /** Deletes pictures no shortcut uses any more: replaced ones, and those of deleted or unsaved shortcuts. */
    fun deleteAllExcept(context: Context, keep: Set<String>) {
        dir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also turns photos the right way up.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > DECODE_MAX) {
                    val f = DECODE_MAX.toFloat() / longest
                    decoder.setTargetSize(max(1, (info.size.width * f).roundToInt()), max(1, (info.size.height * f).roundToInt()))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_MAX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
