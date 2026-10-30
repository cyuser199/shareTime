package cn.sharetime.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.exifinterface.media.ExifInterface
import androidx.core.net.toUri
import androidx.core.graphics.scale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ShareTimeApp : Application() {
    val store by lazy { Store(this) }
}

data class UiState(
    val url: String = "https://cyadmin.xyz/sharetime", val name: String = "", val registered: Boolean = false,
    val connected: Boolean = false, val paired: Boolean = false, val peerName: String = "好友",
    val pairingStatus: String = "", val online: Boolean = false, val code: String = "", val codeUntil: Long = 0,
    val pairRequest: String = "", val pairName: String = "", val fingerprint: String = "",
    val round: String = "", val phase: String = "", val initiator: Boolean = false,
    val submitted: Boolean = false, val peerSubmitted: Boolean = false, val deadline: Long = 0,
    val preview: ByteArray? = null, val received: ByteArray? = null,
    val busy: Boolean = false, val message: String = "", val keepReceiving: Boolean = false,
    val history: List<JournalEntry> = emptyList(), val background: ByteArray? = null,
    val newEntryId: String = "", val completionOrdinal: Int = 0
)

/** Android Keystore wraps credentials; no secrets or images participate in backup. */
private class Vault(context: Context) {
    private val file = File(context.noBackupFilesDir, "credentials.bin")
    private val key: SecretKey
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey("sharetime-v1", null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("sharetime-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    fun read(): JSONObject = try {
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    } catch (_: Exception) { JSONObject() }
    @Synchronized fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val temp = File(file.parentFile, "credentials.tmp")
        temp.writeBytes(cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)))
        check(temp.renameTo(file)) { "无法保存凭据" }
    }
}

class Store(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vault = Vault(context)
    private val journal = JournalStorage(context)
    private var credentials = vault.read().also {
        if (it.optString("url") == "https://192.168.1.191:8443") {
            it.put("url", "https://cyadmin.xyz/sharetime")
            vault.write(it)
        }
    }
    private val mutable = MutableStateFlow(UiState(
        url = credentials.optString("url", "https://cyadmin.xyz/sharetime"), name = credentials.optString("name"),
        registered = credentials.has("token"), paired = credentials.optString("certificate").isNotBlank(),
        code = credentials.optString("fixedCode"), codeUntil = credentials.optLong("codeUntil")
    ))
    val state = mutable.asStateFlow()
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var heartbeat: Job? = null
    @Volatile private var lastServerReply = 0L
    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private var network = connectivity.activeNetwork
    private var backgroundClose: Job? = null
    @Volatile private var wanted = false
    @Volatile private var foreground = false
    val isForeground: Boolean get() = foreground
    private var retries = 0
    private var peerKey = ""
    private var lastNotified = ""

    init {
        scope.launch {
            try { mutable.update { it.copy(history = journal.entries(), background = journal.background()) } }
            catch (_: Exception) { message("本机记录暂时无法读取，请保留应用数据后重试") }
        }
        File(context.cacheDir, "capture").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: android.net.Network) = networkAvailable(available)
            override fun onLost(lost: android.net.Network) = networkLost(lost)
        })
    }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun clearMessage() { message("") }
    fun consumeCompletion() { mutable.update { it.copy(newEntryId = "", completionOrdinal = 0) } }
    suspend fun loadEntry(id: String): JournalRecord? = withContext(Dispatchers.IO) {
        try { journal.load(id) } catch (_: Exception) { message("无法读取这条本机记录"); null }
    }
    fun deleteEntry(id: String) { scope.launch {
        try { journal.delete(id); mutable.update { it.copy(history = journal.entries()) }; message("已删除这台手机上的记录") }
        catch (_: Exception) { message("删除失败，请重试") }
    } }
    fun resetBackground() { scope.launch {
        try { journal.setBackground(null); mutable.update { it.copy(background = null) } }
        catch (_: Exception) { message("背景设置失败，请重试") }
    } }
    fun chooseBackground(uri: Uri) { prepareUri(uri, background = true) }
    private fun archive(current: UiState, bytes: ByteArray) {
        val (entry, ordinal) = journal.add(current.round, current.preview, bytes, current.peerName)
        val entries = journal.entries()
        mutable.update {
            if (it.round == current.round) it.copy(history = entries, newEntryId = if (ordinal > 0) entry.id else "",
                completionOrdinal = ordinal, round = "", phase = "", preview = null, received = null,
                submitted = false, peerSubmitted = false, busy = false)
            else it.copy(history = entries)
        }
        File(context.cacheDir, "capture").listFiles()?.forEach { it.delete() }
        command("ack", JSONObject().put("id", current.round))
    }
    fun retryArchive() { scope.launch {
        val current = state.value
        val bytes = current.received ?: return@launch
        try { archive(current, bytes) }
        catch (_: Exception) { message("本机记录未能保存，请检查剩余空间后重试") }
    } }
    private fun persist() { vault.write(credentials) }
    fun switchServer(url: String) {
        if (state.value.paired) { message("请先解除现有配对再切换服务器，本机记录会保留"); return }
        val normalized = url.trim().trimEnd('/')
        val uri = normalized.toUri()
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) { message("请输入有效的 HTTPS 服务器地址"); return }
        ReceiveService.setEnabled(context, false)
        reset()
        credentials.put("url", normalized); persist()
        mutable.update { it.copy(url = normalized) }
    }
    fun register(url: String, name: String) {
        if (state.value.busy || state.value.registered || !Agreement.accepted(context)) return
        scope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                val normalized = url.trim().trimEnd('/')
                val uri = normalized.toUri()
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入 https://服务器地址:端口" }
                require(name.trim().length in 1..24) { "昵称需为 1 到 24 个字" }
                val keys = Crypto.generate()
                val body = JSONObject().put("name", name.trim()).put("publicKey", keys.second)
                val request = Request.Builder().url("$normalized/register")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "注册失败（${response.code}），请稍后重试" }
                    val result = JSONObject(response.body!!.string())
                    credentials = JSONObject().put("url", normalized).put("name", name.trim())
                        .put("privateKey", keys.first).put("publicKey", keys.second)
                        .put("token", result.getString("token")).put("id", result.getString("id"))
                    persist()
                }
                mutable.update { it.copy(url = normalized, name = name.trim(), registered = true) }
                connect()
                ReceiveService.restore(context)
            } catch (e: Exception) { message(e.message?.take(100) ?: "连接失败，请检查服务器地址与网络") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun onForeground() {
        foreground = true
        Notices.dismiss(context)
        backgroundClose?.cancel()
        connect()
    }
    fun onBackground() {
        foreground = false
        backgroundClose?.cancel()
        backgroundClose = scope.launch {
            delay(30_000)
            if (!state.value.keepReceiving && state.value.round.isBlank()) disconnect()
        }
    }
    fun keepReceiving(enabled: Boolean) {
        mutable.update { it.copy(keepReceiving = enabled) }
        if (enabled) connect() else if (!foreground && state.value.round.isBlank()) disconnect()
    }
    @Synchronized private fun networkAvailable(available: android.net.Network) {
        if (network == available) return
        network = available
        retries = 0
        if (!wanted) return
        heartbeat?.cancel()
        socket?.cancel(); socket = null
        mutable.update { it.copy(connected = false, online = false) }
        connect()
    }
    @Synchronized private fun networkLost(lost: android.net.Network) {
        if (network != lost) return
        network = null
        reconnect?.cancel()
        heartbeat?.cancel()
        socket?.cancel(); socket = null
        mutable.update { it.copy(connected = false, online = false, busy = false) }
    }
    @Synchronized fun connect() {
        if (!state.value.registered || !Agreement.accepted(context)) return
        wanted = true
        if (socket != null || network == null) return
        reconnect?.cancel()
        val url = state.value.url.replaceFirst("https://", "wss://") + "/connect"
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (socket !== ws) return
                heartbeat?.cancel()
                lastServerReply = android.os.SystemClock.elapsedRealtime()
                heartbeat = scope.launch {
                    while (isActive && socket === ws) {
                        delay(1_000)
                        if (android.os.SystemClock.elapsedRealtime() - lastServerReply > 10_000) {
                            ws.cancel(); lost(ws); break
                        }
                        if (!ws.send("{\"type\":\"ping\"}")) { lost(ws); break }
                    }
                }
                ws.send(JSONObject().put("token", credentials.getString("token"))
                    .put("certificate", credentials.optString("certificate").ifBlank { null })
                    .put("pairingProtocol", 2).put("reservation", credentials.optString("reservation").ifBlank { null }).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (socket !== ws) return
                lastServerReply = android.os.SystemClock.elapsedRealtime()
                try { handle(JSONObject(text)) }
                catch (_: Exception) { message("消息处理失败，请结束本轮后重试") }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = lost(ws)
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = lost(ws)
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
        })
    }
    @Synchronized private fun lost(ws: WebSocket) {
        if (socket !== ws) return
        socket = null
        heartbeat?.cancel()
        mutable.update { it.copy(connected = false, online = false, busy = false) }
        if (wanted && network != null) {
            reconnect?.cancel()
            reconnect = scope.launch {
                delay(BackgroundPolicy.retryDelay(retries))
                retries++
                connect()
            }
        }
    }
    @Synchronized fun disconnect() {
        wanted = false
        heartbeat?.cancel()
        reconnect?.cancel()
        socket?.close(1000, null)
        socket = null
        mutable.update { it.copy(connected = false, online = false) }
    }
    fun command(type: String, extra: JSONObject = JSONObject()) {
        val sent = socket?.send(extra.put("type", type).toString()) ?: false
        if (!sent) message("尚未连接服务器，请检查网络后重试")
    }
    @Synchronized fun reconnectNow() {
        reconnect?.cancel()
        heartbeat?.cancel()
        socket?.cancel(); socket = null
        retries = 0
        network = connectivity.activeNetwork
        mutable.update { it.copy(connected = false, online = false, busy = false) }
        connect()
        if (network == null) message("当前没有可用网络，恢复后会自动连接")
    }
    fun invite() { command("invite") }
    fun respond(accept: Boolean) {
        Notices.dismiss(context)
        command("respond", JSONObject().put("id", state.value.round).put("accept", accept))
    }
    fun confirmPair(accept: Boolean) {
        Notices.dismiss(context)
        command("confirm_pair", JSONObject().put("id", state.value.pairRequest).put("accept", accept))
        mutable.update { it.copy(pairRequest = "") }
    }
    fun endRound() {
        if (state.value.phase != "complete") command("cancel", JSONObject().put("id", state.value.round))
        clearRound()
    }
    private fun clearRound() {
        Notices.dismiss(context)
        mutable.update { it.copy(round = "", phase = "", preview = null, received = null,
            submitted = false, peerSubmitted = false, busy = false) }
        File(context.cacheDir, "capture").listFiles()?.forEach { it.delete() }
    }
    fun unpair() {
        if (!state.value.connected) { message("请连接服务器后解除配对"); return }
        command("unpair")
    }
    fun reset() {
        if (state.value.paired) { message("请先解除配对"); return }
        disconnect()
        val url = state.value.url
        credentials = JSONObject().put("url", url)
        persist()
        peerKey = ""
        mutable.value = UiState(url = url, history = state.value.history, background = state.value.background)
    }
    private fun handle(event: JSONObject) {
        when (event.getString("type")) {
            "state" -> {
                retries = 0
                if (event.optBoolean("paired") && credentials.optString("certificate").isBlank()) {
                    // A peer reconnecting with an old certificate cannot undo our local unpair.
                    command("unpair")
                    return
                }
                val peer = event.optJSONObject("peer")
                if (peer != null) peerKey = peer.getString("publicKey")
                mutable.update { it.copy(connected = true, paired = event.optBoolean("paired"),
                    online = event.optBoolean("online"), peerName = peer?.optString("name") ?: "好友",
                    fingerprint = if (peerKey.isNotBlank()) Crypto.fingerprint(credentials.getString("publicKey"), peerKey) else "") }
            }
            "fixed_code" -> {
                credentials.put("fixedCode", event.getString("code")).put("reservation", event.getString("ticket")).put("codeUntil", event.getLong("expiresAt")); persist()
                mutable.update { it.copy(code = event.getString("code"), codeUntil = event.getLong("expiresAt"), pairingStatus = "") }
            }
            "pair_waiting" -> mutable.update { it.copy(pairingStatus = event.optString("message"), busy = false) }
            "code" -> mutable.update { it.copy(code = event.getString("code"), codeUntil = System.currentTimeMillis() + event.getInt("expiresIn") * 1000L) }
            "pair_request" -> {
                mutable.update { it.copy(pairRequest = event.getString("id"), pairName = event.getString("name")) }
                Notices.invitation(context, "有人申请与你配对，打开应用确认")
            }
            "paired" -> {
                credentials.put("certificate", event.getString("certificate")); persist()
                mutable.update { it.copy(paired = true, pairingStatus = "", pairRequest = "") }
                command("paired_ack")
            }
            "unpaired" -> {
                credentials.remove("certificate"); persist()
                peerKey = ""
                clearRound()
                mutable.update { it.copy(paired = false, online = false, fingerprint = "", message = "配对已解除", pairingStatus = "") }
                command("reserve_fixed")
            }
            "round" -> {
                if (event.getString("phase") == "complete" && state.value.history.any { it.id == event.getString("id") }) {
                    command("ack", JSONObject().put("id", event.getString("id")))
                    return
                }
                if (event.getString("phase") != "waiting") Notices.dismiss(context)
                val id = event.getString("id")
                val list = event.getJSONArray("submitted")
                val submitted = (0 until list.length()).map { list.getString(it) }
                val me = credentials.getString("id")
                mutable.update { old ->
                    old.copy(round = id, phase = event.getString("phase"), initiator = event.getString("initiator") == me,
                        submitted = me in submitted, peerSubmitted = submitted.any { it != me }, busy = false,
                        deadline = System.currentTimeMillis() + event.getInt("remaining") * 1000L,
                        preview = if (id == old.round) old.preview else null,
                        received = if (id == old.round) old.received else null)
                }
                if (state.value.phase == "waiting" && !state.value.initiator && lastNotified != id) {
                    lastNotified = id
                    if (!foreground) {
                        Notices.invitation(context, "好友想和你一起记录此刻")
                        InviteOverlay.show(context, id)
                    }
                }
            }
            "record" -> {
                if (state.value.history.any { it.id == event.getString("id") }) {
                    command("ack", JSONObject().put("id", event.getString("id")))
                    return
                }
                val current = state.value
                if (current.round == event.getString("id")) {
                    try {
                        val bytes = Crypto.decrypt(event.getString("body"), Crypto.privateKey(credentials.getString("privateKey")), peerKey, current.round)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                        mutable.update { it.copy(received = bytes, phase = "complete") }
                        archive(current, bytes)
                    } catch (_: Exception) { message("记录未能存入时间轴，请检查空间后点重试保存") }
                }
            }
            "ended" -> { clearRound(); message(event.optString("reason", "本轮已结束")) }
            "notice", "error" -> { mutable.update { it.copy(busy = false) }; message(event.optString("message", "操作失败")) }
        }
    }
    fun prepareUri(uri: Uri, expectedRound: String = state.value.round, background: Boolean = false) {
        scope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                val bitmap = context.contentResolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("无法读取图片")
                val orientation = context.contentResolver.openInputStream(uri).use { input ->
                    if (input != null) ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) else ExifInterface.ORIENTATION_NORMAL
                }
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                    }
                }
                val oriented = if (!matrix.isIdentity) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) else bitmap
                if (oriented !== bitmap) bitmap.recycle()
                prepareBitmap(oriented, expectedRound, background)
            } catch (_: Exception) { message("无法读取图片，请重新选择"); mutable.update { it.copy(busy = false) } }
        }
    }
    fun prepareBitmap(bitmap: Bitmap, expectedRound: String = state.value.round, background: Boolean = false) {
        scope.launch {
            try {
                val factor = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
                val scaled = if (factor < 1) bitmap.scale((bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1)) else bitmap
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()
                require(bytes.size <= 3 * 1024 * 1024)
                if (background) {
                    journal.setBackground(bytes)
                    mutable.update { it.copy(background = bytes, busy = false) }
                    message("已设置这台手机的背景")
                } else mutable.update { if (!it.submitted && it.round.isNotBlank() && it.round == expectedRound) it.copy(preview = bytes, busy = false) else it.copy(busy = false) }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            } catch (_: Exception) { message("图片处理失败，请重试"); mutable.update { it.copy(busy = false) } }
        }
    }
    fun submit() {
        val current = state.value
        if (current.busy || current.submitted || current.phase != "active" || current.preview == null) return
        scope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                val body = Crypto.encrypt(current.preview, Crypto.privateKey(credentials.getString("privateKey")), peerKey, current.round)
                command("submit", JSONObject().put("id", current.round).put("body", body))
                delay(20_000)
                if (state.value.round == current.round && state.value.busy) {
                    mutable.update { it.copy(busy = false) }; message("提交结果未确认，请检查网络后重试")
                }
            } catch (_: Exception) { mutable.update { it.copy(busy = false) }; message("发送失败，请重试") }
        }
    }
}
