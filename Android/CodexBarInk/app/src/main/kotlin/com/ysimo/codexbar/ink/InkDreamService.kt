package com.ysimo.codexbar.ink

import android.graphics.BitmapFactory
import android.graphics.Color
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView

class InkDreamService : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        val bitmap = DashboardScreensaverExporter.lastImageUri(this)?.let { uri ->
            runCatching {
                contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        if (bitmap != null) {
            setContentView(ImageView(this).apply {
                setBackgroundColor(PAPER_COLOR)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
            })
        } else {
            setContentView(TextView(this).apply {
                setBackgroundColor(PAPER_COLOR)
                setTextColor(Color.rgb(21, 21, 21))
                text = "Open CodexBar Ink and export a SCREEN image first"
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            })
        }
    }

    private companion object {
        const val PAPER_COLOR = 0xFFF5F2E9.toInt()
    }
}
