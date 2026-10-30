package cn.sharetime.app

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun JournalBackdrop(custom: ByteArray?, dark: Boolean, content: @Composable () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val colors = if (dark) listOf(Color(0xFF14221D), Color(0xFF121A19), Color(0xFF1C2026))
        else listOf(Color(0xFFEDF6ED), Color(0xFFF9FAF3), Color(0xFFEDF3F6))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors))) {
        val bitmap = remember(custom) { custom?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(background.copy(alpha = if (dark) .82f else .78f)))
        } else {
            val accent = MaterialTheme.colorScheme.primary.copy(alpha = .06f)
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(accent, size.width * .62f, androidx.compose.ui.geometry.Offset(size.width * 1.05f, size.height * .13f))
                drawCircle(accent.copy(alpha = .035f), size.width * .75f, androidx.compose.ui.geometry.Offset(0f, size.height * .80f))
            }
        }
        content()
    }
}

@Composable fun JournalHome(state: UiState, date: LocalDate, onDate: () -> Unit, onToday: () -> Unit,
                             onOpen: (String) -> Unit, onInvite: () -> Unit) {
    val today = LocalDate.now()
    val entries = state.history.filter { it.day == date.toString() && it.mode == state.mode }.sortedBy { it.time }
    if (state.paired) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("和 ${state.peerName}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (!state.connected) "正在连接…" else if (state.online) "好友已连接" else "好友暂未连接",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .70f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = CircleShape) {
                Text("只属于我们", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (state.mode == "duo") {
        Text(if (date == today) "把今天，\n留给彼此。" else "翻开这一天。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
        Text("一张照片，一段当下。\n平凡的日子，也值得被记住。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onInvite, enabled = state.connected,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text("一起记录") }
    } else Text("我们的时间轴", style = MaterialTheme.typography.headlineLarge)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (date == today) "今天的时间轴" else date.format(DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA)) + "的时间轴",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 · EEEE", Locale.CHINA)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDate) { Icon(Icons.Default.DateRange, contentDescription = "选择日期") }
    }
    if (date != today) TextButton(onClick = onToday) { Text("回到今天") }
    if (entries.isEmpty()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .78f)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (date == today) "今天的故事，等你开始" else "这一天还没有记录", style = MaterialTheme.typography.titleMedium)
                Text("完成一次共同记录，这里就会多一个时刻。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        entries.forEach { entry ->
            if (entry.mode == "friends") {
                FriendTimelineEntry(entry, onOpen)
            } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(48.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp).clickable { onOpen(entry.id) }
                        .semantics { contentDescription = "查看第 ${entry.ordinal} 次记录" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                    Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
                }
                Surface(onClick = { onOpen(entry.id) }, Modifier.weight(1f).padding(bottom = 14.dp), shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .90f), tonalElevation = 1.dp) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("和 ${entry.peerName} 的此刻", style = MaterialTheme.typography.titleMedium)
                        Text(if (entry.mode == "friends") "第 ${entry.ordinal} 次群组记录 · 点开查看" else "第 ${entry.ordinal} 次记录 · 点开查看两张图片", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }
        }
    }
    Text("历史记录仅加密保存在这台手机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun JournalDetail(record: JournalRecord?, viewportHeight: androidx.compose.ui.unit.Dp, frameName: String, onImage: (ByteArray) -> Unit) {
    if (record == null) { CircularProgressIndicator(); Text("正在读取本机记录…"); return }
    if (record.entry.mode == "friends") {
        FriendRecordDetail(record, viewportHeight, frameName, onImage)
    } else {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(Instant.ofEpochMilli(record.entry.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M 月 d 日 · HH:mm", Locale.CHINA)),
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("第 ${record.entry.ordinal} 次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    val mine = record.mine
    val available = (viewportHeight - 70.dp).coerceAtLeast(160.dp)
    val imageHeight = if (record.others.isEmpty()) available / 2 else (available / 2).coerceAtLeast(220.dp)
    mine?.let { JournalImageCard(it, "我", frameName, Modifier.fillMaxWidth().height(imageHeight)) { onImage(it) } }
    JournalImageCard(record.peer, record.entry.peerName, frameName, Modifier.fillMaxWidth().height(if (mine == null && record.others.isEmpty()) available else imageHeight)) { onImage(record.peer) }
    record.others.forEach { photo -> JournalImageCard(photo.bytes, photo.name, frameName, Modifier.fillMaxWidth().height(imageHeight)) { onImage(photo.bytes) } }
    }
}

@Composable private fun JournalImageCard(bytes: ByteArray, label: String, frameName: String, modifier: Modifier, onClick: () -> Unit) {
    DisplayFramePicture(bytes, frameName, modifier.clickable(onClick = onClick), Alignment.TopCenter, label)
}

@Composable fun JournalPicture(bytes: ByteArray, modifier: Modifier, alignment: Alignment = Alignment.Center) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bitmap != null) Image(bitmap.asImageBitmap(), "记录图片，点按查看大图", modifier, contentScale = ContentScale.Fit, alignment = alignment)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun JournalCalendar(date: LocalDate, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = JournalDates.pickerMillis(date))
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { onSelect(JournalDates.fromPicker(it)) } }, enabled = picker.selectedDateMillis != null) { Text("查看这一天") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) {
        DatePicker(state = picker, title = { Text("选择记录日期", Modifier.padding(start = 24.dp, top = 20.dp)) })
    }
}

@Composable fun FirstRecordCelebration(onDone: () -> Unit) {
    val animations = remember { ValueAnimator.areAnimatorsEnabled() }
    var entered by remember { mutableStateOf(!animations) }
    val scale by animateFloatAsState(if (entered) 1f else .72f, tween(if (animations) 320 else 0), label = "completion-scale")
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(if (animations) 220 else 0), label = "completion-alpha")
    LaunchedEffect(Unit) { entered = true; delay(if (animations) 2600 else 1600); onDone() }
    Dialog(onDismissRequest = onDone) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Check, null, Modifier.padding(20.dp).size(36.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("恭喜完成\n今天的第一次记录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("这一个时刻，已经留在我们的时间轴里。", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                TextButton(onClick = onDone) { Text("查看时间轴") }
            }
        }
    }
}
