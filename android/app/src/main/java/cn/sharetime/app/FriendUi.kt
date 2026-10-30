package cn.sharetime.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable fun ModeChooser(current: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择记录模式") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("双人模式保留原配对；好友模式使用独立群组，最多 5 人。切换不会清除历史。")
            Button(onClick = { onChoose("duo") }, modifier = Modifier.fillMaxWidth()) { Text("双人模式 · 默认") }
            OutlinedButton(onClick = { onChoose("friends") }, modifier = Modifier.fillMaxWidth()) { Text("好友模式 · 最多 5 人") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (current == "friends") "继续好友模式" else "继续双人模式") } })
}

@Composable fun FriendPanel(state: UiState, store: Store, onCopy: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Text("好友模式", style = MaterialTheme.typography.headlineSmall)
    if (!state.paired) {
        Text("创建一个最多 5 人的群组，或使用好友发来的 8 位群组码加入。双人配对会保留。")
        Button(onClick = { store.command("group_create") }, enabled = state.connected, modifier = Modifier.fillMaxWidth()) { Text("创建好友群组") }
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("8 位好友群组码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { store.command("group_join", JSONObject().put("code", code)) }, enabled = state.connected && code.length == 8, modifier = Modifier.fillMaxWidth()) { Text("加入好友群组") }
    } else {
        Text("群组成员 ${state.groupMembers.size}/5", style = MaterialTheme.typography.titleMedium)
        state.groupMembers.forEach { Text("${it.name}${if (it.id == state.me) "（我）" else ""} · ${if (it.online) "在线" else "离线"}", style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCopy(state.groupCode) }) { Text("复制群组码") }
            TextButton(onClick = { store.command("group_code") }, enabled = state.connected) { Text("更新邀请码") }
        }
        Text("群组码 ${state.groupCode.chunked(4).joinToString(" ")} · 邀请码 3 天有效，仅分享给信任的好友。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun FriendWaiting(state: UiState, store: Store) {
    Text("一起记录此刻", style = MaterialTheme.typography.headlineSmall)
    Text("发起人已经开始记录，你可以现在加入。首次图片提交后停止加入，未加入的好友不会阻塞本轮。")
    Text("已加入 ${state.accepted.size} 人", style = MaterialTheme.typography.titleMedium)
    state.groupMembers.filter { it.id in state.accepted }.forEach { Text(it.name) }
    Button(onClick = { store.respond(true) }, enabled = state.connected, modifier = Modifier.fillMaxWidth()) { Text("加入并记录") }
    TextButton(onClick = { store.respond(false) }, enabled = state.connected) { Text("本轮暂不参加") }
}
