package cn.sharetime.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

enum class FrameStyle(val label: String) { WHITE("简白"), BLACK("墨黑"), INSTANT("暖纸"), FILM("胶片") }

/** Local display rendering. Adds margins outside the entire photo, without cropping. */
object PhotoFrames {
    fun render(bytes: ByteArray, style: FrameStyle, width: Float): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }))
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        val source = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (source !== decoded) decoded.recycle()
        try {
            val border = (min(source.width, source.height) * width.coerceIn(.02f, .12f)).toInt().coerceAtLeast(2)
            val bottom = border
            val output = Bitmap.createBitmap(source.width + border * 2, source.height + border + bottom, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(when (style) {
                    FrameStyle.BLACK, FrameStyle.FILM -> Color.rgb(25, 26, 27)
                    FrameStyle.INSTANT -> Color.rgb(250, 247, 239)
                    else -> Color.WHITE
                })
                canvas.drawBitmap(source, border.toFloat(), border.toFloat(), null)
                if (style == FrameStyle.WHITE || style == FrameStyle.INSTANT) {
                    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (style == FrameStyle.WHITE) Color.rgb(225, 226, 223) else Color.rgb(219, 213, 201)
                        this.style = Paint.Style.STROKE
                        strokeWidth = (min(source.width, source.height) * .0015f).coerceAtLeast(1f)
                    }
                    val inset = outline.strokeWidth / 2
                    canvas.drawRect(inset, inset, output.width - inset, output.height - inset, outline)
                }
                if (style == FrameStyle.FILM) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 238, 221) }
                    val hole = (border * .42f).coerceAtLeast(1f)
                    val count = (output.height / (border * 1.7f)).toInt().coerceAtLeast(1)
                    val gap = (output.height - count * hole) / (count + 1)
                    repeat(count) { index ->
                        val y = gap + index * (hole + gap)
                        val left = (border - hole) / 2
                        for (x in listOf(left, output.width - left - hole))
                            canvas.drawRoundRect(x, y, x + hole, y + hole, hole * .18f, hole * .18f, paint)
                    }
                }
                return ByteArrayOutputStream().use { out ->
                    check(output.compress(Bitmap.CompressFormat.JPEG, 94, out))
                    out.toByteArray()
                }
            } finally { output.recycle() }
        } finally { source.recycle() }
    }
}
