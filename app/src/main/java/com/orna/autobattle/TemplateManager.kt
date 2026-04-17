package com.orna.autobattle

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

object TemplateManager {

    private const val TAG = "TemplateManager"

    data class Template(val name: String, val bitmap: Bitmap)

    private val loaded = mutableListOf<Template>()

    fun init(ctx: Context) {
        loaded.forEach { it.bitmap.recycle() }
        loaded.clear()
        SpriteStorage.list(ctx).forEach { (name, uri) ->
            val bmp = SpriteStorage.load(ctx, uri)
            if (bmp != null) {
                loaded.add(Template(name, bmp))
            }
        }
        Log.d(TAG, "Loaded ${loaded.size} templates from Downloads/${SpriteStorage.FOLDER}")
    }

    fun all(): List<Template> = loaded

    fun count(): Int = loaded.size

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
        val name = "capture_${System.currentTimeMillis()}"
        return if (SpriteStorage.save(ctx, name, cropped)) {
            val template = Template(name, cropped)
            loaded.add(template)
            Log.d(TAG, "Saved capture $name")
            template
        } else {
            cropped.recycle()
            null
        }
    }

    fun deleteAll(ctx: Context) {
        SpriteStorage.deleteAll(ctx)
        loaded.forEach { it.bitmap.recycle() }
        loaded.clear()
    }

    fun delete(ctx: Context, name: String) {
        SpriteStorage.deleteByName(ctx, name)
        val idx = loaded.indexOfFirst { it.name == name }
        if (idx >= 0) {
            loaded[idx].bitmap.recycle()
            loaded.removeAt(idx)
        }
    }
}
