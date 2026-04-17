package com.orna.autobattle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Saves and loads monster sprite templates to/from internal storage.
 *
 * Each template is a small PNG cropped from a real game screenshot.
 * Templates are stored in <filesDir>/monster_templates/.
 * The engine matches all loaded templates every frame and taps on any match.
 */
object TemplateManager {

    private const val TAG = "TemplateManager"
    private const val DIR = "monster_templates"

    data class Template(val name: String, val bitmap: Bitmap)

    private val loaded = mutableListOf<Template>()

    fun init(ctx: Context) {
        loaded.clear()
        templatesDir(ctx).listFiles()?.forEach { file ->
            if (file.extension == "png") {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    loaded.add(Template(file.nameWithoutExtension, bmp))
                    Log.d(TAG, "Loaded template: ${file.name} (${bmp.width}x${bmp.height})")
                }
            }
        }
        Log.d(TAG, "Total templates: ${loaded.size}")
    }

    fun all(): List<Template> = loaded

    fun count(): Int = loaded.size

    /**
     * Save a cropped region of [frame] as a new template.
     * [cx], [cy] is the centre of the monster; [size] is the crop box half-size.
     * Returns the saved template, or null on error.
     */
    fun captureFromFrame(
        ctx: Context,
        frame: Bitmap,
        cx: Int,
        cy: Int,
        size: Int = 32
    ): Template? {
        val left = (cx - size).coerceAtLeast(0)
        val top = (cy - size).coerceAtLeast(0)
        val right = (cx + size).coerceAtMost(frame.width)
        val bottom = (cy + size).coerceAtMost(frame.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        val cropped = Bitmap.createBitmap(frame, left, top, w, h)
        val name = "monster_${System.currentTimeMillis()}"
        val file = File(templatesDir(ctx), "$name.png")
        file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val template = Template(name, cropped)
        loaded.add(template)
        Log.d(TAG, "Saved template $name (${w}x${h})")
        return template
    }

    fun deleteAll(ctx: Context) {
        templatesDir(ctx).listFiles()?.forEach { it.delete() }
        loaded.forEach { it.bitmap.recycle() }
        loaded.clear()
    }

    fun delete(ctx: Context, name: String) {
        File(templatesDir(ctx), "$name.png").delete()
        val idx = loaded.indexOfFirst { it.name == name }
        if (idx >= 0) {
            loaded[idx].bitmap.recycle()
            loaded.removeAt(idx)
        }
    }

    private fun templatesDir(ctx: Context): File =
        File(ctx.filesDir, DIR).also { it.mkdirs() }
}
