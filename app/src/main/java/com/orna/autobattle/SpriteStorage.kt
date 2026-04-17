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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(ctx, name, bitmap)
        } else {
            saveFile(ctx, name, bitmap)
        }
    }

    private fun saveMediaStore(ctx: Context, name: String, bitmap: Bitmap): Boolean {
        // Delete any stale entry first so re-downloads don't create duplicates
        deleteByName(ctx, name)

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

    fun slugSet(ctx: Context): Set<String> = list(ctx).map { it.first }.toSet()

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
    }
}
