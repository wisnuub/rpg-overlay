package com.orna.autobattle

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

object TemplateManager {

    private const val TAG = "TemplateManager"

    /**
     * matchBitmap is the template scaled to MATCH_TARGET_PX for SAD comparison.
     * bitmap is the full-res original stored on disk (kept for display/export).
     */
    data class Template(val name: String, val bitmap: Bitmap, val matchBitmap: Bitmap)

    private val loaded = mutableListOf<Template>()

    // In-game monsters render at roughly this pixel size on a 1080p screen.
    // Codex sprites are usually larger; we scale them down for SAD matching.
    const val MATCH_TARGET_PX = 56

    private fun scaleForMatch(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim == 0) return src
        val w = (src.width.toFloat() / maxDim * MATCH_TARGET_PX).toInt().coerceAtLeast(1)
        val h = (src.height.toFloat() / maxDim * MATCH_TARGET_PX).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun init(ctx: Context) {
        loaded.forEach {
            it.bitmap.recycle()
            if (it.matchBitmap !== it.bitmap) it.matchBitmap.recycle()
        }
        loaded.clear()
        SpriteStorage.list(ctx).forEach { (name, uri) ->
            val bmp = SpriteStorage.load(ctx, uri) ?: return@forEach
            loaded.add(Template(name, bmp, scaleForMatch(bmp)))
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
            val template = Template(name, cropped, scaleForMatch(cropped))
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
        loaded.forEach {
            it.bitmap.recycle()
            if (it.matchBitmap !== it.bitmap) it.matchBitmap.recycle()
        }
        loaded.clear()
    }

    fun delete(ctx: Context, name: String) {
        SpriteStorage.deleteByName(ctx, name)
        val idx = loaded.indexOfFirst { it.name == name }
        if (idx >= 0) {
            loaded[idx].bitmap.recycle()
            if (loaded[idx].matchBitmap !== loaded[idx].bitmap) loaded[idx].matchBitmap.recycle()
            loaded.removeAt(idx)
        }
    }
}
