package com.fantasyanalyzer.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.fantasyanalyzer.analysis.RecapLine
import java.io.File

/**
 * Draws a week's recap as a PNG card (dark, like the app) and opens the share
 * sheet with it, plus the same recap as text for apps that only take text.
 */
object RecapShare {
    private const val WIDTH = 1080
    private const val PAD = 64f
    private const val ROW = 150f

    fun share(context: Context, title: String, subtitle: String, lines: List<RecapLine>) {
        val file = render(context, title, subtitle, lines)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val text = buildString {
            appendLine(title)
            lines.forEach { appendLine("${it.label}: ${it.headline} — ${it.detail}") }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text.trim())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share recap").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun render(context: Context, title: String, subtitle: String, lines: List<RecapLine>): File {
        val height = (PAD * 2 + 190f + ROW * lines.size + 60f).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF1B1D21.toInt())
        fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val white = 0xFFFFFFFF.toInt()
        val muted = 0xFFA9AEB8.toInt()
        val accent = 0xFF6EA8FE.toInt()
        c.drawText(title, PAD, PAD + 64f, paint(64f, white, bold = true))
        c.drawText(subtitle, PAD, PAD + 120f, paint(36f, muted))
        var y = PAD + 190f
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262930.toInt() }
        lines.forEach { l ->
            c.drawRoundRect(RectF(PAD - 16f, y - 8f, WIDTH - PAD + 16f, y + ROW - 22f), 28f, 28f, card)
            c.drawText(l.label.uppercase(), PAD + 16f, y + 38f, paint(28f, accent, bold = true))
            c.drawText(ellipsize(l.headline, 46f, WIDTH - 2 * PAD), PAD + 16f, y + 86f, paint(46f, white, bold = true))
            c.drawText(ellipsize(l.detail, 32f, WIDTH - 2 * PAD), PAD + 16f, y + 122f, paint(32f, muted))
            y += ROW
        }
        c.drawText("Fantasy Analyzer", PAD, height - PAD + 10f, paint(28f, muted))
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "recap.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    /** Cuts [text] with "…" so it fits [maxWidth] at [size]. */
    private fun ellipsize(text: String, size: Float, maxWidth: Float): String {
        val p = Paint().apply { textSize = size; typeface = Typeface.DEFAULT_BOLD }
        if (p.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
