package cn.sharetime.app

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** FCM is an extra wake-up channel. The direct connection remains available on every device. */
class PushChannels(private val context: Context, private val status: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private var lastSuccess = 0L
    private var identity = ""
    private var requestedKey = ""
    private var lastAttempt = 0L

    fun refresh(key: String, force: Boolean = false, serverReady: suspend () -> Boolean, upload: suspend (String) -> Boolean) {
        if (job?.isActive == true && requestedKey == key && !force) return
        if (!force && requestedKey == key && System.currentTimeMillis() - lastAttempt < 60_000) return
        job?.cancel()
        requestedKey = key
        lastAttempt = System.currentTimeMillis()
        job = scope.launch {
            mutex.withLock {
                if (!Agreement.accepted(context) || !ReceiveService.enabled(context)) {
                    status("后台接收已关闭"); return@withLock
                }
                if (GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
                    status("后台长连接 · Google 服务不可用"); return@withLock
                }
                if (!force && key == identity && System.currentTimeMillis() - lastSuccess < 3_600_000) return@withLock
                status("后台长连接 · 正在检查 FCM")
                try {
                    if (!serverReady()) { status("后台长连接 · 服务器未启用 FCM"); return@withLock }
                    val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
                    if (app == null) { status("后台长连接 · 未配置 FCM"); return@withLock }
                    val messaging = FirebaseMessaging.getInstance()
                    messaging.isAutoInitEnabled = true
                    val token = withTimeout(20_000) {
                        suspendCancellableCoroutine<String> { continuation ->
                            messaging.token.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                                .addOnFailureListener { if (continuation.isActive) continuation.resume("") }
                        }
                    }
                    val registered = token.isNotBlank() && upload(token)
                    currentCoroutineContext().ensureActive()
                    if (registered) {
                        identity = key; lastSuccess = System.currentTimeMillis()
                        status("FCM 推送 + 后台长连接保障")
                    } else status("后台长连接 · FCM 服务暂不可用")
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    status("后台长连接 · FCM 暂不可用")
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); identity = ""; lastSuccess = 0; lastAttempt = 0; requestedKey = ""
        if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseMessaging.getInstance().isAutoInitEnabled = false
        status("后台接收已关闭")
    }
}

class ShareTimeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Do not log or expose this registration token.
        if (Agreement.accepted(this) && ReceiveService.enabled(this))
            (application as ShareTimeApp).store.refreshPush(true)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!Agreement.accepted(this) || !ReceiveService.enabled(this)) return
        val store = (application as ShareTimeApp).store
        if (!store.state.value.registered) return
        if (message.data["mode"] != store.state.value.mode) return
        val event = message.data["event"]
        if (event !in listOf("invite", "records")) return
        val id = message.data["eventId"] ?: return
        if (!id.matches(Regex("[a-f0-9]{32}"))) return
        val preferences = getSharedPreferences("push-received", Context.MODE_PRIVATE)
        val seen = preferences.getStringSet("ids", emptySet()).orEmpty()
        if (id in seen) return
        preferences.edit().putStringSet("ids", (seen.takeLastCompat(63) + id).toSet()).apply()
        if (!store.isForeground) Notices.invitation(this,
            if (event == "invite") "好友邀请你一起记录，点按查看" else "好友有新的记录内容，点按查看")
        // High-priority messages can restore the user-enabled service; a downgraded
        // message must not try an otherwise disallowed background service start.
        if (message.priority == RemoteMessage.PRIORITY_HIGH) ReceiveService.restore(this)
        store.ensureConnected()
    }

    private fun Set<String>.takeLastCompat(count: Int) = toList().takeLast(count)
}
