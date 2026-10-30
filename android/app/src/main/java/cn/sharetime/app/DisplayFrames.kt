package cn.sharetime.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun DisplayFramePicture(bytes: ByteArray, frameName: String, modifier: Modifier, alignment: Alignment = Alignment.Center, label: String? = null) {
    val display by produceState(bytes, bytes, frameName) {
        value = bytes
        val style = FrameStyle.entries.firstOrNull { it.name == frameName }
        if (style != null) {
            try { value = withContext(Dispatchers.Default) { PhotoFrames.render(bytes, style, .045f) } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
        }
    }
    val ratio = remember(display) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(display, 0, display.size, bounds)
        if (bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else 1f
    }
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val width = minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.size(width, width / ratio)) {
            JournalPicture(display, Modifier.fillMaxSize())
            if (label != null) Surface(Modifier.align(Alignment.TopStart).padding(8.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = .55f), contentColor = Color.White) {
                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable fun DisplayFrameSettings(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("展示相框") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("用于本机记录详情和大图展示，保存到相册仍为原图。")
            (listOf("NONE" to "无相框") + FrameStyle.entries.map { it.name to it.label }).forEach { (name, label) ->
                FilterChip(selected = name == selected, onClick = { onSelect(name) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
