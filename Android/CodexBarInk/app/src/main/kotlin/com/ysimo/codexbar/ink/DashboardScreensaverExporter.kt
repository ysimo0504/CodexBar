package com.ysimo.codexbar.ink

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DashboardScreensaverExporter(private val context: Context) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun export(root: View, hiddenView: View, completion: (Result<Uri>) -> Unit) {
        val previousAlpha = hiddenView.alpha
        hiddenView.alpha = 0f
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        hiddenView.alpha = previousAlpha

        executor.execute {
            val result = runCatching { write(bitmap) }
            bitmap.recycle()
            root.post { completion(result) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun write(bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val existing = preferences.getString(KEY_URI, null)?.let(Uri::parse)
        if (existing != null) {
            val updated = runCatching {
                resolver.openOutputStream(existing, "w")?.use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Could not encode screensaver image"
                    }
                } ?: error("Screensaver image is unavailable")
                resolver.update(
                    existing,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1_000)
                    },
                    null,
                    null,
                )
                existing
            }.getOrNull()
            if (updated != null) return updated
            preferences.edit().remove(KEY_URI).apply()
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CodexBar Ink")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create screensaver image")
        try {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Could not encode screensaver image"
                }
            } ?: error("Could not open screensaver image")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        preferences.edit().putString(KEY_URI, uri.toString()).apply()
        return uri
    }

    companion object {
        private const val PREFERENCES_NAME = "screensaver_export_v1"
        private const val KEY_URI = "last_image_uri"
        private const val FILE_NAME = "codexbar-ink-screensaver.png"

        fun lastImageUri(context: Context): Uri? = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)
            ?.let(Uri::parse)
    }
}
