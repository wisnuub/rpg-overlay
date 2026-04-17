package com.orna.autobattle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL

object CodexFetcher {

    private const val TAG = "CodexFetcher"
    private const val BASE = "https://playorna.com"
    private const val LIST_URL = "$BASE/codex/monsters/"
    private const val SPRITES_URL = "$BASE/static/img/monsters/"
    private const val TARGET_PX = 56
    private const val CONCURRENT = 5
    private const val PAGE_DELAY_MS = 200L

    data class Progress(val done: Int, val total: Int, val current: String)

    suspend fun fetchAll(
        ctx: Context,
        onProgress: (Progress) -> Unit,
        onDone: (downloaded: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { onProgress(Progress(0, 0, "Fetching monster list…")) }
        val monsters = fetchAllSlugs().distinctBy { it.first }
        Log.d(TAG, "Total unique monsters: ${monsters.size}")
        downloadList(ctx, monsters, onProgress, onDone)
    }

    suspend fun checkForNew(ctx: Context): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val existing = SpriteStorage.slugSet(ctx)
            fetchAllSlugs().distinctBy { it.first }.filter { (slug, _) -> slug !in existing }
        }

    suspend fun downloadNew(
        ctx: Context,
        newMonsters: List<Pair<String, String>>,
        onProgress: (Progress) -> Unit,
        onDone: (downloaded: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (newMonsters.isEmpty()) {
            withContext(Dispatchers.Main) { onDone(0, 0) }
            return@withContext
        }
        downloadList(ctx, newMonsters, onProgress, onDone)
    }

    private suspend fun downloadList(
        ctx: Context,
        monsters: List<Pair<String, String>>,
        onProgress: (Progress) -> Unit,
        onDone: (downloaded: Int, total: Int) -> Unit
    ) = coroutineScope {
        val existing = SpriteStorage.slugSet(ctx)
        val semaphore = Semaphore(CONCURRENT)
        var done = 0

        val jobs = monsters.map { (slug, name) ->
            async {
                semaphore.withPermit {
                    val saved = if (slug in existing) {
                        true
                    } else {
                        val bmp = downloadSprite(slug)
                        if (bmp != null) {
                            val scaled = scaleBitmap(bmp, TARGET_PX)
                            bmp.recycle()
                            val ok = SpriteStorage.save(ctx, slug, scaled)
                            scaled.recycle()
                            ok
                        } else false
                    }
                    val current = synchronized(this@CodexFetcher) { ++done }
                    withContext(Dispatchers.Main) {
                        onProgress(Progress(current, monsters.size, name))
                    }
                    saved
                }
            }
        }

        val results = jobs.awaitAll()
        val downloaded = results.count { it }
        withContext(Dispatchers.Main) { onDone(downloaded, monsters.size) }
    }

    private suspend fun fetchAllSlugs(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val monsters = mutableListOf<Pair<String, String>>()
            var page = 1
            while (true) {
                val url = if (page == 1) LIST_URL else "$LIST_URL?p=$page"
                val html = fetchText(url) ?: break
                val found = parseMonsterSlugs(html)
                monsters.addAll(found)
                Log.d(TAG, "Page $page: ${found.size} monsters")
                if (!html.contains("Next page", ignoreCase = true)) break
                page++
                delay(PAGE_DELAY_MS)
            }
            monsters
        }

    private fun parseMonsterSlugs(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
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
        val underscored = slug.replace("-", "_")
        for (suffix in listOf("1", "")) {
            val url = "$SPRITES_URL${underscored}${suffix}.png"
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

    private fun fetchText(urlStr: String): String? = try {
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
