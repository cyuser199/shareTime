package cn.sharetime.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable private fun People(names: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        names.take(5).forEach { name ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Text(name.take(1), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable fun FriendTimelineEntry(entry: JournalEntry, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(24.dp).clickable { onOpen(entry.id) }.semantics { contentDescription = "查看第 ${entry.ordinal} 次记录" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
            Box(Modifier.width(1.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Surface(onClick = { onOpen(entry.id) }, modifier = Modifier.weight(1f).padding(bottom = 8.dp),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(if (entry.photoCount > 0) "${entry.participantNames.size} 人 · ${entry.photoCount} 张" else "好友记录",
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                People(entry.participantNames.ifEmpty { listOf("我", entry.peerName) })
                Text(entry.participantNames.ifEmpty { listOf("我", entry.peerName) }.joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("第 ${entry.ordinal} 次共同记录  ·  点开查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable fun FriendRecordDetail(record: JournalRecord, viewportHeight: Dp, frameName: String, onImage: (ByteArray) -> Unit) {
    val photos = listOfNotNull(record.mine?.let { LocalPhoto("我", it) }) + LocalPhoto(record.entry.peerName, record.peer) + record.others
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .90f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Instant.ofEpochMilli(record.entry.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M 月 d 日 · HH:mm")),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${photos.size} 张记录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                photos.forEach { photo ->
                    AssistChip(onClick = { onImage(photo.bytes) }, label = { Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp)) })
                }
            }
        }
    }
    PhotoArrangement(record.entry.id, photos, viewportHeight, frameName, onImage)
}

private data class PhotoPosition(val x: Float, val y: Float, val size: Float)
private fun trianglePositions(count: Int): List<PhotoPosition> = List(count) { i ->
    when {
        count == 1 -> PhotoPosition(.5f, .25f, .68f)
        count == 2 -> PhotoPosition(if (i == 0) .05f else .95f, if (i == 0) .05f else .75f, .48f)
        i == 0 -> PhotoPosition(.5f, 0f, if (count <= 3) .46f else .30f)
        else -> PhotoPosition(if (i % 2 == 1) .02f else .98f, if (count <= 3 || i > 2) 1f else .5f, if (count <= 3) .46f else .30f)
    }
}

@Composable private fun PhotoArrangement(id: String, photos: List<LocalPhoto>, viewportHeight: Dp, frameName: String, onImage: (ByteArray) -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("photo-arrangements", android.content.Context.MODE_PRIVATE)
    var layout by remember(id) { mutableStateOf(prefs.getString("$id-mode", "vertical") ?: "vertical") }
    var positions by remember(id, photos.size) { mutableStateOf(runCatching {
        val a = JSONArray(prefs.getString("$id-positions", "[]"))
        require(a.length() == photos.size)
        List(a.length()) { i -> a.getJSONObject(i).let { PhotoPosition(it.getDouble("x").toFloat().coerceIn(0f,1f), it.getDouble("y").toFloat().coerceIn(0f,1f), it.getDouble("size").toFloat().coerceIn(.28f,.85f)) } }
    }.getOrElse { trianglePositions(photos.size) }) }
    var selected by remember(id) { mutableIntStateOf(0) }
    fun savePositions() {
        prefs.edit().putString("$id-positions", JSONArray().apply { positions.forEach { put(JSONObject().put("x",it.x).put("y",it.y).put("size",it.size)) } }.toString()).apply()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("vertical" to "竖向", "triangle" to "三角", "custom" to "自定义").forEach { (key, label) ->
            FilterChip(selected = layout == key, onClick = { layout = key; prefs.edit().putString("$id-mode", key).apply() }, label = { Text(label) })
        }
    }
    if (layout == "vertical") {
        VerticalFriendPhotos(photos, viewportHeight, frameName, onImage)
        return
    }
    if (layout == "custom") {
        Text("选择图片后拖动摆放，滑动调整大小；点成员名字可查看原图。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            photos.forEachIndexed { i, photo -> FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text(photo.name) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("大小", style = MaterialTheme.typography.labelMedium)
            Slider(value = positions[selected].size, onValueChange = { size -> positions = positions.mapIndexed { i,p -> if (i == selected) p.copy(size = size) else p } },
                onValueChangeFinished = { savePositions() }, valueRange = .28f.. .85f, modifier = Modifier.weight(1f))
            TextButton(onClick = { positions = trianglePositions(photos.size); savePositions() }) { Text("重置") }
        }
    }
    val density = LocalDensity.current
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height((viewportHeight - 200.dp).coerceIn(400.dp, 680.dp))) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val shown = if (layout == "triangle") trianglePositions(photos.size) else positions
            shown.forEachIndexed { i, p ->
                val w = maxWidth * p.size
                val h = (maxHeight * p.size).coerceAtMost(maxHeight * .85f)
                val freeX = (widthPx - with(density) { w.toPx() }).coerceAtLeast(1f)
                val freeY = (heightPx - with(density) { h.toPx() }).coerceAtLeast(1f)
                val position = Modifier.offset { IntOffset((freeX * p.x).roundToInt(), (freeY * p.y).roundToInt()) }.size(w, h)
                val interaction = if (layout == "custom") Modifier.pointerInput(id, i, freeX, freeY) {
                    detectDragGestures(onDragStart = { selected = i }, onDragEnd = { savePositions() }, onDragCancel = { savePositions() }) { change, amount ->
                        change.consume()
                        positions = positions.mapIndexed { index, old -> if (index == i) old.copy(x = (old.x + amount.x/freeX).coerceIn(0f,1f), y = (old.y + amount.y/freeY).coerceIn(0f,1f)) else old }
                    }
                } else Modifier.clickable { onImage(photos[i].bytes) }
                DisplayFramePicture(photos[i].bytes, frameName, position.then(interaction), Alignment.Center, photos[i].name)
            }
        }
    }
}

@Composable private fun VerticalFriendPhotos(photos: List<LocalPhoto>, viewportHeight: Dp, frameName: String, onImage: (ByteArray) -> Unit) {
    val available = (viewportHeight - 150.dp).coerceAtLeast(200.dp)
    val height = if (photos.size <= 2) available / photos.size else (available * .72f).coerceIn(240.dp, 440.dp)
    photos.forEachIndexed { index, photo ->
        DisplayFramePicture(photo.bytes, frameName, Modifier.fillMaxWidth().height(height).clickable { onImage(photo.bytes) },
            Alignment.TopCenter, "${photo.name} · ${index + 1}/${photos.size}")
    }
}
