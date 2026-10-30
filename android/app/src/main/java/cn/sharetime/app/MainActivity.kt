package cn.sharetime.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val store get() = (application as ShareTimeApp).store
    private var cameraUri: Uri? = null
    private var cameraRound = ""
    private var pickerRound = ""
    private var projectionRound = ""
    private var clipboardCode by mutableStateOf("")
    private var ignoredPhrase = ""
    private var update by mutableStateOf<AppUpdate?>(null)
    private var updateChecking by mutableStateOf(false)
    private var pendingSave: ByteArray? = null
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { store.prepareUri(it, cameraRound) }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { store.prepareUri(it, pickerRound) } }
    private val backgroundPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { store.chooseBackground(it) } }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) store.message("通知未开启，后台邀请可能无法提醒；应用内仍可接收")
    }
    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bytes = pendingSave; pendingSave = null
        if (granted && bytes != null) save(bytes) else store.message("未保存：需要允许保存到相册")
    }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java).putExtra("data", result.data).putExtra("round", projectionRound))
            moveTaskToBack(true)
        } else store.message("截图已取消，你仍可拍照或选择手机自带截图")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraUri = savedInstanceState?.getString("cameraUri")?.let(Uri::parse)
        cameraRound = savedInstanceState?.getString("cameraRound") ?: ""
        pickerRound = savedInstanceState?.getString("pickerRound") ?: ""
        projectionRound = savedInstanceState?.getString("projectionRound") ?: ""
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        Notices.channels(this)
        setContent { ShareTimeScreen() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("cameraUri", cameraUri?.toString())
        outState.putString("cameraRound", cameraRound)
        outState.putString("pickerRound", pickerRound)
        outState.putString("projectionRound", projectionRound)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); if (Agreement.accepted(this)) { store.onForeground(); ReceiveService.restore(this) } }
    override fun onStop() { store.onBackground(); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Agreement.accepted(this)) lifecycleScope.launch {
            delay(700)
            readPairClipboard()
        }
    }
    private fun readPairClipboard(manual: Boolean = false) {
        val state = store.state.value
        if (!hasWindowFocus() || !state.registered || state.paired) return
        try {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
            val code = PairPhrase.parse(text)
            if (code != null && code != state.code && (manual || text != ignoredPhrase)) {
                ignoredPhrase = text; clipboardCode = code
            } else if (manual) store.message("没有找到可用的此刻好友口令")
        } catch (_: Exception) { if (manual) store.message("暂时无法读取剪贴板，请手动输入配对码") }
    }
    private fun copyPair(code: String, phrase: Boolean) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("此刻配对", if (phrase) PairPhrase.create(code) else code))
        store.message(if (phrase) "好友口令已复制，发给好友后打开此刻即可识别" else "配对码已复制")
    }
    private fun checkUpdate(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        lifecycleScope.launch {
            try { update = Updates.check(store.state.value.url); if (manual && update == null) store.message("已经是最新版本 ${BuildConfig.VERSION_NAME}") }
            catch (_: Exception) { if (manual) store.message("检查更新失败，请稍后重试") }
            finally { updateChecking = false }
        }
    }
    private fun openSystemSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { store.message("此手机不支持该入口，请在系统设置中打开此刻的应用权限") }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun takePhoto() {
        try {
            val file = File(cacheDir, "capture/photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
            cameraUri = FileProvider.getUriForFile(this, "$packageName.files", file)
            cameraRound = store.state.value.round
            camera.launch(cameraUri!!)
        } catch (_: Exception) { store.message("未找到可用的系统相机") }
    }
    private fun save(bytes: ByteArray) {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = bytes; writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val name = "ShareTime_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ShareTime")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        } else {
                            @Suppress("DEPRECATION")
                            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ShareTime").apply { mkdirs() }
                            @Suppress("DEPRECATION")
                            put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建图片")
                    try {
                        contentResolver.openOutputStream(uri).use { output -> checkNotNull(output).write(bytes) }
                        if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
                }
                store.message("已保存到相册 / ShareTime")
            } catch (_: Exception) { store.message("保存失败，请检查相册权限与存储空间") }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable private fun ShareTimeScreen() {
        val state by store.state.collectAsStateWithLifecycle()
        val dark = isSystemInDarkTheme()
        val scheme = if (dark) darkColorScheme(primary = Color(0xFFA3D4B8), secondary = Color(0xFFB4CCBE),
            onPrimary = Color(0xFF113828), onSecondary = Color(0xFF203D2D),
            primaryContainer = Color(0xFF244C3D), onPrimaryContainer = Color(0xFFD3F3DE),
            secondaryContainer = Color(0xFF2D4237), onSecondaryContainer = Color(0xFFD9EBDD),
            background = Color(0xFF121A16), surface = Color(0xFF17211B))
            else lightColorScheme(primary = Color(0xFF326B54), secondary = Color(0xFF51665A),
                onPrimary = Color.White, onSecondary = Color.White,
                primaryContainer = Color(0xFFD9EBDD), onPrimaryContainer = Color(0xFF234936),
                secondaryContainer = Color(0xFFE2EDE3), onSecondaryContainer = Color(0xFF304C3B),
                background = Color(0xFFFAFCF8), surface = Color(0xFFFAFCF8))
        MaterialTheme(colorScheme = scheme) {
            var agreed by remember { mutableStateOf(Agreement.accepted(this)) }
            var showAgreement by remember { mutableStateOf(false) }
            if (!agreed) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text("欢迎使用此刻", style = MaterialTheme.typography.headlineMedium)
                        Text(Agreement.text, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp))
                        Button(onClick = { Agreement.accept(this@MainActivity); agreed = true; store.onForeground() }, Modifier.fillMaxWidth()) { Text("已阅读并同意，开始使用") }
                        TextButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("不同意，退出") }
                    }
                }
                return@MaterialTheme
            }
            if (showAgreement) AlertDialog(onDismissRequest = { showAgreement = false }, title = { Text("使用协议") },
                text = { Text(Agreement.text, Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { showAgreement = false }) { Text("关闭") } })
            val prefs = remember { getSharedPreferences("preferences", MODE_PRIVATE) }
            var permissionGuide by rememberSaveable { mutableStateOf(!prefs.getBoolean("permissions_guided", false)) }
            LaunchedEffect(state.connected, permissionGuide) {
                if (state.connected && !permissionGuide) { readPairClipboard(); checkUpdate(false) }
            }
            if (permissionGuide) AlertDialog(onDismissRequest = {}, title = { Text("开始使用 · 权限设置") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("先设置邀请提醒。拍照、截图和保存权限会在使用对应功能时按需处理；拒绝提醒权限仍可在应用内操作。")
                    OutlinedButton(onClick = { requestNotifications() }) { Text("开启系统通知") }
                    OutlinedButton(onClick = { openSystemSettings(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) { Text("开启小窗提醒（可选）") }
                    Text("悬浮窗仅提醒，4 秒自动消失，无需确认。")
                } }, confirmButton = { TextButton(onClick = { prefs.edit().putBoolean("permissions_guided", true).apply(); permissionGuide = false }) { Text("完成，进入应用") } })
            if (clipboardCode.isNotBlank() && !permissionGuide) AlertDialog(onDismissRequest = { clipboardCode = "" }, title = { Text("发现好友口令") },
                text = { Text("是否使用剪贴板中的此刻口令申请配对？需生成口令的好友确认。") },
                confirmButton = { TextButton(onClick = { store.command("join", org.json.JSONObject().put("code", clipboardCode)); clipboardCode = "" }, enabled = state.connected) { Text("申请连接") } },
                dismissButton = { TextButton(onClick = { clipboardCode = "" }) { Text("忽略") } })
            update?.let { item -> AlertDialog(onDismissRequest = { update = null }, title = { Text("发现新版本 ${item.version}") },
                text = { Text(item.notes + "\n\n下载后按系统提示安装，覆盖安装保留本机数据。") },
                confirmButton = { TextButton(onClick = { openSystemSettings(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))); update = null }) { Text("下载更新") } },
                dismissButton = { TextButton(onClick = { update = null }) { Text("稍后") } }) }
            var settings by rememberSaveable { mutableStateOf(false) }
            var confirmEnd by rememberSaveable { mutableStateOf(false) }
            var confirmUnpair by rememberSaveable { mutableStateOf(false) }
            var expandedImage by remember { mutableStateOf<ByteArray?>(null) }
            var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
            var selectedEntry by rememberSaveable { mutableStateOf<String?>(null) }
            var historyBrowsing by rememberSaveable { mutableStateOf(false) }
            var showCalendar by rememberSaveable { mutableStateOf(false) }
            var showCelebration by rememberSaveable { mutableStateOf(false) }
            var deleteEntry by rememberSaveable { mutableStateOf(false) }
            var detail by remember(selectedEntry) { mutableStateOf<JournalRecord?>(null) }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(selectedEntry) { selectedEntry?.let { detail = store.loadEntry(it) } }
            LaunchedEffect(state.newEntryId) {
                if (state.newEntryId.isNotBlank()) {
                    state.history.firstOrNull { it.id == state.newEntryId }?.let { selectedDay = it.day }
                    selectedEntry = null
                    settings = false
                    historyBrowsing = true
                    if (state.completionOrdinal == 1) showCelebration = true
                    else if (state.completionOrdinal > 1) store.message("已完成今天的第 ${state.completionOrdinal} 次记录")
                    store.consumeCompletion()
                }
            }
            LaunchedEffect(state.round) {
                expandedImage = null
                confirmEnd = false
                if (state.round.isNotBlank()) {
                    selectedEntry = null; settings = false; historyBrowsing = false
                    showCalendar = false; showCelebration = false
                }
            }
            LaunchedEffect(state.message) {
                if (state.message.isNotBlank()) { snackbar.showSnackbar(state.message); store.clearMessage() }
            }
            BackHandler(enabled = settings || state.round.isNotBlank() || expandedImage != null || selectedEntry != null || historyBrowsing) {
                when {
                    expandedImage != null -> expandedImage = null
                    selectedEntry != null -> selectedEntry = null
                    settings -> settings = false
                    state.round.isNotBlank() -> confirmEnd = true
                    else -> historyBrowsing = false
                }
            }
            JournalBackdrop(state.background, dark) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                topBar = { TopAppBar(title = { Text(if (settings) "设置" else if (selectedEntry != null) "记录详情" else "此刻", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .88f)),
                    navigationIcon = { if (selectedEntry != null) TextButton(onClick = { selectedEntry = null }) { Text("返回") } },
                    actions = {
                        if (selectedEntry == null && (state.paired || state.history.isNotEmpty())) TextButton(onClick = { showCalendar = true }, enabled = state.round.isBlank()) { Text("日历") }
                        if (selectedEntry == null) TextButton(onClick = { settings = !settings }) { Text(if (settings) "完成" else "设置") }
                    }) },
                snackbarHost = { SnackbarHost(snackbar) },
                contentWindowInsets = WindowInsets.safeDrawing
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = if (selectedEntry != null) 16.dp else 24.dp, vertical = if (selectedEntry != null) 12.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(if (selectedEntry != null) 12.dp else 18.dp)) {
                        if (settings) {
                            Text("连接与隐私", style = MaterialTheme.typography.headlineSmall)
                            Text("本软件由CY个人开发")
                            Text("版本 ${BuildConfig.VERSION_NAME}")
                            OutlinedButton(onClick = { checkUpdate(true) }, enabled = !updateChecking) { Text(if (updateChecking) "正在检查…" else "检查更新") }
                            TextButton(onClick = { showAgreement = true }) { Text("使用协议与隐私说明") }
                            OutlinedButton(onClick = { openSystemSettings(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) { Text("允许邀请悬浮窗") }
                            Text("可选权限：在其他应用上方显示邀请卡片。锁屏或勿扰时使用系统通知，不强行弹出。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { openSystemSettings(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("后台省电设置") }
                            OutlinedButton(onClick = { openSystemSettings(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("应用后台 / 自启动设置") }
                            Text("建议允许后台运行和自启动，并在系统通知中允许横幅与锁屏通知；不同品牌入口可能不同。强制停止、断网和深度休眠仍会影响送达。", style = MaterialTheme.typography.bodySmall)
                            Text(state.url, style = MaterialTheme.typography.bodyMedium)
                            Text(if (state.connected) "服务器已连接" else "连接已断开，正在尝试重连")
                            if (state.registered) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("保持接收邀请"); Text("显示持续通知；系统省电仍可能影响送达", style = MaterialTheme.typography.bodySmall) }
                                    Switch(modifier = Modifier.semantics { contentDescription = "保持接收邀请" }, checked = state.keepReceiving, onCheckedChange = { enabled ->
                                        if (enabled) {
                                            requestNotifications()
                                            ReceiveService.setEnabled(this@MainActivity, true)
                                        } else ReceiveService.setEnabled(this@MainActivity, false)
                                    })
                                }
                                OutlinedButton(onClick = { requestNotifications(); startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }) { Text("系统通知设置") }
                            }
                            if (state.fingerprint.isNotBlank()) {
                                Text("配对安全码  ${state.fingerprint}", fontWeight = FontWeight.Medium)
                                Text("与好友核对，两端应显示相同安全码。", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("背景与外观", style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy) { Text("选择背景图片") }
                            if (state.background != null) TextButton(onClick = { store.resetBackground() }) { Text("恢复默认背景") }
                            Text("背景只在这台手机显示，图片会自动添加柔和遮罩，保证文字清晰。", style = MaterialTheme.typography.bodySmall)
                            Text("记录完成后，两张图片会加密保存在本机时间轴，支持按日历查看与删除。服务器仅在内存中转发，双方收到后清理中转内容；未完成的记录最多保留 10 分钟。")
                            Text("服务器只持久保存累计注册设备数和服务密钥。重装会重新计数。离线邀请最多等待 24 小时，对方重新连接后接收；服务器重启会清除等待邀请。", style = MaterialTheme.typography.bodySmall)
                            if (state.paired) OutlinedButton(onClick = { confirmUnpair = true }) { Text("解除配对") }
                            else if (state.registered) OutlinedButton(onClick = { ReceiveService.setEnabled(this@MainActivity, false); store.reset(); settings = false }) { Text("更换服务器 / 重置本机身份") }
                        } else if (selectedEntry != null) {
                            JournalDetail(detail, onImage = { expandedImage = it })
                        } else if (historyBrowsing && state.round.isBlank()) {
                            JournalHome(state, LocalDate.parse(selectedDay), onDate = { showCalendar = true }, onToday = { selectedDay = LocalDate.now().toString() },
                                onOpen = { selectedEntry = it }, onInvite = { requestNotifications(); store.invite() })
                        } else if (!state.registered) {
                            Registration(state)
                        } else if (!state.paired) {
                            Pairing(state)
                        } else if (state.round.isBlank()) {
                            JournalHome(state, LocalDate.parse(selectedDay), onDate = { showCalendar = true }, onToday = { selectedDay = LocalDate.now().toString() },
                                onOpen = { selectedEntry = it }, onInvite = { requestNotifications(); store.invite() })
                        } else {
                            Round(state, onImage = { expandedImage = it }, onEnd = { confirmEnd = true })
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            }
            if (showCalendar) JournalCalendar(LocalDate.parse(selectedDay), onSelect = {
                selectedDay = it.toString(); selectedEntry = null; settings = false
                historyBrowsing = true; showCalendar = false
            }, onDismiss = { showCalendar = false })
            if (showCelebration) FirstRecordCelebration(onDone = { showCelebration = false })
            if (deleteEntry) AlertDialog(onDismissRequest = { deleteEntry = false }, title = { Text("删除这条本机记录？") },
                text = { Text("仅删除这台手机时间轴中的两张图片，不影响好友的记录和已保存到相册的副本。") },
                confirmButton = { TextButton(onClick = { selectedEntry?.let(store::deleteEntry); selectedEntry = null; deleteEntry = false }) { Text("删除记录") } },
                dismissButton = { TextButton(onClick = { deleteEntry = false }) { Text("取消") } })
            if (state.pairRequest.isNotBlank()) AlertDialog(onDismissRequest = { store.confirmPair(false) },
                title = { Text("确认配对") }, text = { Text("${state.pairName} 想与你配对，是否同意？") },
                confirmButton = { TextButton(onClick = { store.confirmPair(true) }) { Text("同意配对") } },
                dismissButton = { TextButton(onClick = { store.confirmPair(false) }) { Text("拒绝") } })
            if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("结束本轮？") },
                text = { Text("未完成的临时图片将被清理。已存入时间轴和相册的记录会保留。") },
                confirmButton = { TextButton(onClick = { store.endRound(); confirmEnd = false }) { Text("结束并清理") } },
                dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("继续记录") } })
            if (confirmUnpair) AlertDialog(onDismissRequest = { confirmUnpair = false }, title = { Text("解除与好友的配对？") },
                text = { Text("当前记录也会结束。再次连接需要重新配对。") },
                confirmButton = { TextButton(onClick = { store.unpair(); confirmUnpair = false }) { Text("解除配对") } },
                dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("取消") } })
            expandedImage?.let { bytes ->
                androidx.compose.ui.window.Dialog(onDismissRequest = { expandedImage = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        var imageMenu by remember { mutableStateOf(false) }
                        Column(Modifier.safeDrawingPadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { expandedImage = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭图片") }
                                Text("图片", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Box {
                                    IconButton(onClick = { imageMenu = true }) { Icon(Icons.Default.MoreVert, "图片操作") }
                                    DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                                        DropdownMenuItem(text = { Text("保存图片") }, onClick = { imageMenu = false; expandedImage = null; save(bytes) })
                                        if (selectedEntry != null) DropdownMenuItem(text = { Text("删除这条记录", color = MaterialTheme.colorScheme.error) },
                                            onClick = { imageMenu = false; expandedImage = null; deleteEntry = true })
                                    }
                                }
                            }
                            Picture(bytes, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
    @Composable private fun Registration(state: UiState) {
        var url by rememberSaveable { mutableStateOf(state.url) }
        var name by rememberSaveable { mutableStateOf(state.name) }
        Text("把此刻，\n分享给一个人。", style = MaterialTheme.typography.headlineLarge)
        Text("先连接服务器，再和好友配对。无需手机号或账号。")
        OutlinedTextField(name, { name = it.take(24) }, label = { Text("你的昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, supportingText = { Text("需要 HTTPS；默认服务器仅在局域网可达") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { requestNotifications(); store.register(url, name) }, enabled = !state.busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (state.busy) "正在连接…" else "连接服务器")
        }
    }
    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun Pairing(state: UiState) {
        var code by rememberSaveable { mutableStateOf("") }
        Text("连接你的好友", style = MaterialTheme.typography.headlineLarge)
        Text(if (state.connected) "${state.name}，只需一人生成配对码。" else "正在连接服务器…")
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("我的配对码", style = MaterialTheme.typography.titleMedium)
                if (state.code.isNotBlank()) {
                    Text(state.code.chunked(4).joinToString(" "), style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { copyPair(state.code, false) }).semantics { contentDescription = "配对码，长按复制" })
                    Text("长按配对码复制", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { copyPair(state.code, true) }, enabled = System.currentTimeMillis() < state.codeUntil) { Text("复制好友口令") }
                    Countdown(state.codeUntil, "配对码有效时间")
                }
                FilledTonalButton(onClick = { store.command("create_code") }, enabled = state.connected) { Text(if (state.code.isBlank()) "生成配对码" else "重新生成") }
            }
        }
        Text("或者，输入好友的配对码")
        OutlinedButton(onClick = { readPairClipboard(true) }) { Text("读取剪贴板好友口令") }
        Text("生成配对码后可复制好友口令；仅在应用前台识别此刻口令，不上传其他剪贴板内容。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("8 位配对码") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { store.command("join", org.json.JSONObject().put("code", code)) }, enabled = state.connected && code.length == 8,
            modifier = Modifier.fillMaxWidth()) { Text("申请配对") }
        Text("配对码 5 分钟有效，需要生成端确认。", style = MaterialTheme.typography.bodySmall)
    }
    @Composable private fun Round(state: UiState, onImage: (ByteArray) -> Unit, onEnd: () -> Unit) {
        val awaiting = state.phase == "waiting"
        val incoming = awaiting && !state.initiator
        Text(if (state.phase == "complete") "我们的此刻" else if (incoming) "一起记录此刻？" else "记录此刻", style = MaterialTheme.typography.headlineLarge)
        Text(when { state.phase == "complete" -> "这一刻，我们都在。"; incoming -> "${state.peerName} 发来邀请，由你决定是否加入。"; awaiting -> if (state.online) "等待 ${state.peerName} 同意，你可以先准备图片。" else "好友暂未在线，正在等待对方连接。你可以先准备图片，邀请最多等待 24 小时。"; else -> "拍张照片，或截下当前的画面。" })
        if (state.phase != "complete") Countdown(state.deadline, if (awaiting) "邀请剩余时间" else "本轮剩余时间")
        if (incoming) {
            Button(onClick = { store.respond(true) }, Modifier.fillMaxWidth(), enabled = state.connected) { Text("同意，一起记录") }
            OutlinedButton(onClick = { store.respond(false) }, Modifier.fillMaxWidth(), enabled = state.connected) { Text("暂时不方便") }
        } else if (state.phase != "complete") {
            if (!state.submitted) {
                FilledTonalButton(onClick = { takePhoto() }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("用系统相机拍照") }
                OutlinedButton(onClick = {
                    requestNotifications()
                    projectionRound = state.round
                    projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("截取屏幕") }
                Text("授权后 5 秒截取一张，请切换到想记录的画面。受保护页面可能无法截图。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pickerRound = state.round; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy) { Text("选择手机自带截图 / 图片") }
            }
            state.preview?.let { bytes -> Picture(bytes, Modifier.fillMaxWidth().height(260.dp).clickable { onImage(bytes) }) }
            Button(onClick = { store.submit() }, enabled = state.connected && !awaiting && !state.submitted && !state.busy && state.preview != null,
                modifier = Modifier.fillMaxWidth()) { Text(when { state.submitted -> "已提交，等待好友"; state.busy -> "正在处理…"; awaiting -> "对方同意后可提交"; else -> "确认提交" }) }
            Text(if (state.peerSubmitted) "好友已提交" else "好友尚未提交", style = MaterialTheme.typography.bodySmall)
            Text("双方均提交后，才会互相展示。", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedButton(onClick = { store.retryArchive() }, enabled = state.received != null) { Text("重试保存到时间轴") }
            Text("我的此刻", style = MaterialTheme.typography.titleMedium)
            state.preview?.let { bytes ->
                Picture(bytes, Modifier.fillMaxWidth().height(280.dp).clickable { onImage(bytes) })
            } ?: Text("本机临时图片已清理")
            Text("${state.peerName} 的此刻", style = MaterialTheme.typography.titleMedium)
            state.received?.let { bytes ->
                Picture(bytes, Modifier.fillMaxWidth().height(280.dp).clickable { onImage(bytes) })
            } ?: Text("正在接收图片…")
        }
        if (!incoming) TextButton(onClick = onEnd) { Text(if (state.phase == "complete") "结束并清理" else "取消本轮") }
    }
    @Composable private fun Countdown(deadline: Long, label: String) {
        var remaining by remember(deadline) { mutableLongStateOf(0) }
        LaunchedEffect(deadline) { while (true) { remaining = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0); delay(1000) } }
        val formatted = if (remaining >= 3600) "${remaining / 3600} 小时 ${(remaining % 3600) / 60} 分" else "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
        Text("$label  $formatted", style = MaterialTheme.typography.bodySmall)
    }
    @Composable private fun Picture(bytes: ByteArray, modifier: Modifier) {
        val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "记录图片，点按查看大图", modifier = modifier, contentScale = ContentScale.Fit)
    }
}
