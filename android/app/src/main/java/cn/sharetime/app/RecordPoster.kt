package cn.sharetime.app

import android.graphics.*
import android.text.TextUtils
import android.text.TextPaint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class PosterArrangement(val mode: String, val width: Float, val canvasHeight: Float,
    val photoHeight: Float, val positions: List<PhotoPosition> = emptyList())

/** Local composition, independent of scroll position and screen resolution. */
object RecordPoster {
    fun hour(time: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(time).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 · HH 时"))

    fun render(record: JournalRecord, photos: List<LocalPhoto>, frameName: String, arrangement: PosterArrangement): ByteArray {
        require(photos.isNotEmpty() && arrangement.width > 0)
        val vertical = arrangement.mode == "vertical"
        val areaHeight = if (vertical) photos.size * arrangement.photoHeight + (photos.size - 1) * 10f else arrangement.canvasHeight
        require(areaHeight > 0 && (vertical || arrangement.positions.size == photos.size))
        val margin = 16f
        val header = 100f
        val footer = 30f
        val logicalWidth = arrangement.width + margin * 2
        val logicalHeight = header + areaHeight + footer
        // Limit allocation on Android 8 handsets, including tall five-person posters.
        val scale = minOf(1080f / logicalWidth, 8192f / logicalHeight)
        val bitmap = Bitmap.createBitmap((logicalWidth * scale).roundToInt(), (logicalHeight * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.drawColor(Color.rgb(244, 248, 241))
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(29, 56, 44); textSize = 25f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
            canvas.drawText("我们的此刻", margin, 35f, text)
            text.textSize = 14f
            text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText(hour(record.entry.time), margin, 61f, text)
            text.textSize = 11f
            text.color = Color.rgb(88, 109, 97)
            canvas.drawText("${photos.size} 张记录 · 同一小时的我们", margin, 82f, text)
            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            photos.forEachIndexed { i, photo ->
                val style = FrameStyle.entries.firstOrNull { it.name == frameName }
                val bytes = if (style == null) photo.bytes else PhotoFrames.render(photo.bytes, style, .045f)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
                try {
                    val box = if (vertical) PhotoBox(0f, i * (arrangement.photoHeight + 10f), arrangement.width, arrangement.photoHeight)
                        else PosterLayout.box(arrangement.width, areaHeight, arrangement.positions[i])
                    val fit = PosterLayout.fit(box, image.width, image.height, vertical)
                    val left = margin + fit.x
                    val top = header + fit.y
                    canvas.drawBitmap(image, null, RectF(left, top, left + fit.width, top + fit.height), photoPaint)
                    // Name stays inside the actual fitted image, as in the editor.
                    val labelSize = minOf(12f, fit.width / 5).coerceAtLeast(4f)
                    val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = labelSize }
                    val name = TextUtils.ellipsize(photo.name, label, (fit.width - 24f).coerceAtLeast(4f), TextUtils.TruncateAt.END).toString()
                    val pad = minOf(8f, fit.width * .04f)
                    val badge = RectF(left + pad, top + pad, left + pad + label.measureText(name) + pad * 2, top + pad + labelSize + pad * 2)
                    canvas.drawRoundRect(badge, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) })
                    canvas.drawText(name, badge.left + pad, badge.top + pad - label.fontMetrics.ascent, label)
                } finally { image.recycle() }
            }
            text.textSize = 10f
            canvas.drawText("此刻 · 留下彼此的当下", margin, logicalHeight - 10f, text)
            return ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)); out.toByteArray() }
        } finally { bitmap.recycle() }
    }
}

@Composable fun PosterExportButton(record: JournalRecord, photos: List<LocalPhoto>, frameName: String,
    mode: String = "vertical", positions: List<PhotoPosition> = emptyList(), canvasHeight: Dp = 500.dp,
    photoHeight: Dp, onExport: (ByteArray) -> Unit) {
    var exporting by remember(record.entry.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val snapshot = PosterArrangement(mode, maxWidth.value, canvasHeight.value, photoHeight.value, positions.toList())
        OutlinedButton(enabled = !exporting, modifier = Modifier.fillMaxWidth(), onClick = {
            exporting = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.Default) { RecordPoster.render(record, photos, frameName, snapshot) }
                    onExport(bytes)
                } catch (e: CancellationException) { throw e }
                catch (_: OutOfMemoryError) { Toast.makeText(context, "内存不足，请关闭其他应用后重试", Toast.LENGTH_LONG).show() }
                catch (_: Exception) { Toast.makeText(context, "海报导出失败，请重试", Toast.LENGTH_LONG).show() }
                finally { exporting = false }
            }
        }) { Text(if (exporting) "正在生成海报…" else "一键导出海报") }
    }
}
