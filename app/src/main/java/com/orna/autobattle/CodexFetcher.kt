package com.orna.autobattle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads monster sprites from the Orna codex website and stores them in
 * the same directory as TemplateManager so they are picked up as templates.
 *
 * Sprite URL pattern: https://playorna.com/static/img/monsters/<slug_underscored>1.png
 *
 * Call [fetchAll] once (e.g., from a "Download sprites" button in MainActivity).
 * Progress is reported via [onProgress]. On completion, call TemplateManager.init()
 * so the new sprites are loaded into memory.
 */
object CodexFetcher {

    private const val TAG = "CodexFetcher"
    private const val BASE = "https://playorna.com"
    private const val LIST_URL = "$BASE/codex/monsters/"
    private const val SPRITES_URL = "$BASE/static/img/monsters/"

    // In-game monsters appear roughly 40–80 px tall on a 1080-wide screen.
    // Codex sprites are typically 64×64 game pixels, displayed larger on the
    // codex page. We resize downloaded sprites to this target height so that
    // template matching operates at the right scale.
    private const val TARGET_PX = 56

    data class Progress(val done: Int, val total: Int, val current: String)

    /**
     * Download all monster sprites from the codex.
     * Runs on [Dispatchers.IO]. Call from a coroutine scope.
     *
     * @param onProgress called on main thread with progress updates
     * @param onDone     called on main thread when finished
     */
    suspend fun fetchAll(
        ctx: Context,
        onProgress: (Progress) -> Unit,
        onDone: (downloaded: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val outDir = File(ctx.filesDir, "monster_templates").also { it.mkdirs() }

        // Step 1: collect all monster slugs by paginating the codex
        val monsters = mutableListOf<Pair<String, String>>() // (slug, name)
        withContext(Dispatchers.Main) { onProgress(Progress(0, 0, "Fetching monster list…")) }
        var page = 1
        while (true) {
            val url = if (page == 1) LIST_URL else "$LIST_URL?p=$page"
            val html = fetchText(url) ?: break
            val found = parseMonsterSlugs(html)
            monsters.addAll(found)
            Log.d(TAG, "Page $page: ${found.size} monsters")
            if (!html.contains("Next page", ignoreCase = true)) break
            page++
            delay(400)
        }

        // Deduplicate
        val unique = monsters.distinctBy { it.first }
        Log.d(TAG, "Total unique monsters: ${unique.size}")

        // Step 2: download each sprite
        var downloaded = 0
        unique.forEachIndexed { idx, (slug, name) ->
            val dest = File(outDir, "$slug.png")
            if (!dest.exists()) {
                val bmp = downloadSprite(slug)
                if (bmp != null) {
                    val scaled = scaleBitmap(bmp, TARGET_PX)
                    bmp.recycle()
                    dest.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    scaled.recycle()
                    downloaded++
                    Log.d(TAG, "Saved: $slug")
                }
            } else {
                downloaded++ // already have it
            }
            withContext(Dispatchers.Main) {
                onProgress(Progress(idx + 1, unique.size, name))
            }
            delay(350) // polite rate limit
        }

        withContext(Dispatchers.Main) { onDone(downloaded, unique.size) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMonsterSlugs(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        // Match href="/codex/monsters/<slug>/" and the link text
        val linkRe = Regex("""href="/codex/monsters/([^/"]+)/"""")
        val nameRe = Regex("""href="/codex/monsters/[^/"]+/"[^>]*>([^<]+)<""")
        val hrefMatches = linkRe.findAll(html).toList()
        val nameMatches = nameRe.findAll(html).toList()
        hrefMatches.forEachIndexed { i, m ->
            val slug = m.groupValues[1]
            val name = nameMatches.getOrNull(i)?.groupValues?.get(1)?.trim() ?: slug
            if (slug.isNotEmpty()) results.add(slug to name)
        }
        return results
    }

    private fun downloadSprite(slug: String): Bitmap? {
        val underscoredSlug = slug.replace("-", "_")
        // Try variant "1" first, then no suffix
        for (suffix in listOf("1", "")) {
            val url = "$SPRITES_URL${underscoredSlug}${suffix}.png"
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "OrnaAuto/1.0")
                if (conn.responseCode == 200) {
                    return BitmapFactory.decodeStream(conn.inputStream)
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed $url: ${e.message}")
            }
        }
        return null
    }

    private fun scaleBitmap(src: Bitmap, targetHeight: Int): Bitmap {
        if (src.height <= 0) return src
        val scale = targetHeight.toFloat() / src.height
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, targetHeight, true)
    }

    private fun fetchText(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "OrnaAuto/1.0")
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            text
        } catch (e: Exception) {
            Log.e(TAG, "fetchText failed: $urlStr — ${e.message}")
            null
        }
    }
}
