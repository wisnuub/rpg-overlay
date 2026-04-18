package com.orna.autobattle

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

// SharedPreferences-backed cache of slug names that have been saved to disk.
// This is the source of truth for "what have we downloaded?" — avoids needing
// READ_MEDIA_IMAGES permission for the existence check, and is instant.
private const val PREFS_NAME = "sprite_slugs"
private const val PREFS_KEY  = "slugs"

private fun cachedSlugs(ctx: Context): Set<String> =
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(PREFS_KEY, emptySet()) ?: emptySet()

private fun addToCache(ctx: Context, slug: String) {
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val updated = (prefs.getStringSet(PREFS_KEY, emptySet()) ?: emptySet()).toMutableSet()
    if (updated.add(slug)) prefs.edit().putStringSet(PREFS_KEY, updated).apply()
}

private fun clearCache(ctx: Context) {
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().remove(PREFS_KEY).apply()
}

/**
 * Stores monster sprites in Downloads/OrnaAutoSprites/ so they survive app uninstalls.
 * Uses MediaStore on API 29+ (no permission needed for writing).
 * Requires READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE (API 29-32) to read
 * files back after a reinstall.
 */
object SpriteStorage {

    private const val TAG = "SpriteStorage"
    const val FOLDER = "OrnaAutoSprites"
    private const val RELATIVE_PATH = "Download/$FOLDER"

    // ── Write ─────────────────────────────────────────────────────────────────

    fun save(ctx: Context, name: String, bitmap: Bitmap): Boolean {
        ensureNomedia(ctx)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(ctx, name, bitmap)
        } else {
            saveFile(ctx, name, bitmap)
        }
        if (ok) addToCache(ctx, name)
        return ok
    }

    private fun existsInMediaStore(ctx: Context, name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return (ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf("$RELATIVE_PATH%", "$name.png"), null
        )?.use { it.count } ?: 0) > 0
    }

    private fun ensureNomedia(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER
            ).also { it.mkdirs() }
            File(dir, ".nomedia").createNewFile()
            return
        }
        // For API 29+, insert a .nomedia file into MediaStore so gallery apps skip the folder
        val existing = ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf("$RELATIVE_PATH%", ".nomedia"), null
        )?.use { it.count } ?: 0
        if (existing > 0) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, ".nomedia")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
        }
        ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun saveMediaStore(ctx: Context, name: String, bitmap: Bitmap): Boolean {
        // Skip if already in MediaStore — avoids duplicate insertions
        if (existsInMediaStore(ctx, name)) return true

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return false.also { Log.e(TAG, "insert failed for $name") }

        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            true
        } catch (e: IOException) {
            Log.e(TAG, "write failed for $name: ${e.message}")
            ctx.contentResolver.delete(uri, null, null)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveFile(ctx: Context, name: String, bitmap: Bitmap): Boolean {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER
            ).also { it.mkdirs() }
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveFile failed for $name: ${e.message}")
            false
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns (nameWithoutExtension, contentUri) for every sprite in our folder. */
    fun list(ctx: Context): List<Pair<String, Uri>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listMediaStore(ctx)
        } else {
            listFiles(ctx)
        }
    }

    private fun listMediaStore(ctx: Context): List<Pair<String, Uri>> {
        val result = mutableListOf<Pair<String, Uri>>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf("$RELATIVE_PATH%"), null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (c.moveToNext()) {
                val displayName = c.getString(nameCol) ?: continue
                if (!displayName.endsWith(".png", ignoreCase = true)) continue
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
                result.add(displayName.removeSuffix(".png") to uri)
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun listFiles(ctx: Context): List<Pair<String, Uri>> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER
        )
        return dir.listFiles { f -> f.extension == "png" }
            ?.map { it.nameWithoutExtension to Uri.fromFile(it) }
            ?: emptyList()
    }

    fun load(ctx: Context, uri: Uri): Bitmap? = try {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.e(TAG, "load failed $uri: ${e.message}")
        null
    }

    /**
     * Returns the set of downloaded slug names.
     * If the cache is empty (fresh install / reinstall), scans Downloads/OrnaAutoSprites/
     * via MediaStore to rebuild it from whatever is already on disk.
     * Requires READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE (API <33) to find
     * files from a previous install; if permission is absent the scan returns nothing and
     * the caller will re-download everything (safe fallback).
     */
    fun slugSet(ctx: Context): Set<String> {
        val cached = cachedSlugs(ctx)
        if (cached.isNotEmpty()) return cached
        return rebuildCacheFromDisk(ctx)
    }

    /** Scans the on-disk folder and repopulates SharedPreferences. Returns the new set. */
    fun rebuildCacheFromDisk(ctx: Context): Set<String> {
        val found = list(ctx).map { it.first }.toSet()
        if (found.isNotEmpty()) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(PREFS_KEY, found).apply()
            Log.d(TAG, "Rebuilt slug cache from disk: ${found.size} sprites")
        }
        return found
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    fun deleteByName(ctx: Context, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf("$RELATIVE_PATH%", "$name.png")
            )
        }
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet(PREFS_KEY, emptySet()) ?: emptySet())
            .toMutableSet().also { it.remove(name) }
        prefs.edit().putStringSet(PREFS_KEY, updated).apply()
    }

    fun deleteAll(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("$RELATIVE_PATH%")
            )
        } else {
            @Suppress("DEPRECATION")
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER
            ).deleteRecursively()
        }
        clearCache(ctx)
    }
}
