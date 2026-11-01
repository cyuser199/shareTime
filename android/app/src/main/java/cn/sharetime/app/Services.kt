package cn.sharetime.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap

object Notices {
    fun dismiss(context: Context) { NotificationManagerCompat.from(context).cancel(102); InviteOverlay.hide() }
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("invitations", "好友邀请", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("connection", "连接与截图", NotificationManager.IMPORTANCE_LOW))
    }
    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun notification(context: Context, text: String, ongoing: Boolean = false, reconnect: Boolean = false): Notification {
        channels(context)
        val builder = NotificationCompat.Builder(context, if (ongoing) "connection" else "invitations")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("此刻").setContentText(text)
            .setSilent(ongoing).setOnlyAlertOnce(ongoing)
            .setContentIntent(open(context)).setOngoing(ongoing).setAutoCancel(!ongoing)
            .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (reconnect) builder.addAction(0, "重新连接", PendingIntent.getService(context, 10,
            Intent(context, ReceiveService::class.java).setAction("reconnect"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }
    fun invitation(context: Context, text: String) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(102, notification(context, text))
        }
    }
}

class ReceiveService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var lastText = ""

    companion object {
        fun enabled(context: Context) = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("receive", true)
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putBoolean("receive", enabled).apply()
            if (enabled) restore(context) else {
                context.stopService(Intent(context, ReceiveService::class.java))
                (context.applicationContext as ShareTimeApp).store.keepReceiving(false)
            }
        }
        fun restore(context: Context) {
            if (enabled(context) && Agreement.accepted(context) && (context.applicationContext as ShareTimeApp).store.state.value.registered) {
                try { ContextCompat.startForegroundService(context, Intent(context, ReceiveService::class.java)) }
                catch (_: RuntimeException) { /* Background start may be restricted; the invitation notification still works. */ }
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Agreement.accepted(this) || !enabled(this)) { stopSelf(); return START_NOT_STICKY }
        val store = (application as ShareTimeApp).store
        val status = if (store.state.value.connected) "已连接服务器 · 后台接收邀请" else "正在连接服务器 · 自动重连"
        ServiceCompat.startForeground(this, 100, Notices.notification(this, status, true, true),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        store.keepReceiving(true)
        if (intent?.action == "reconnect") store.reconnectNow()
        if (monitor == null) monitor = serviceScope.launch {
            launch {
                store.state.collect { state ->
                    val text = if (state.connected) "已连接服务器 · 后台接收邀请" else "连接已断开 · 自动重连中"
                    if (text != lastText) {
                        lastText = text
                        getSystemService(NotificationManager::class.java).notify(100, Notices.notification(this@ReceiveService, text, true, true))
                    }
                }
            }
            while (isActive) { delay(20_000); store.ensureConnected(); store.refreshPush() }
        }
        return START_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (enabled(this)) (application as ShareTimeApp).store.ensureConnected()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        serviceScope.cancel()
        (application as ShareTimeApp).store.keepReceiving(false)
        super.onDestroy()
    }
}

/** One user-approved projection, one frame, then release all screen access. */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var expectedRound = ""
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        expectedRound = intent?.getStringExtra("round") ?: ""
        ServiceCompat.startForeground(this, 101, Notices.notification(this, "5 秒后截取一张图片，请切换到想记录的画面", true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        try {
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("data") ?: error("缺少截图授权")
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(Activity.RESULT_OK, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!done) fail("截图已取消") }
            }, handler)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader!!.setOnImageAvailableListener({ source ->
                val next = source.acquireLatestImage()
                if (next != null) { latest?.close(); latest = next }
            }, handler)
            display = projection!!.createVirtualDisplay("ShareTime single screenshot", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            Toast.makeText(this, "5 秒后截图，请切换到想记录的画面", Toast.LENGTH_LONG).show()
            handler.postDelayed({ capture() }, 5000)
        } catch (_: Exception) { fail("无法开始截图，请重新授权") }
        return START_NOT_STICKY
    }
    private fun capture() {
        if (done) return
        try {
            val frame = latest ?: error("没有可用画面")
            val plane = frame.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = createBitmap(paddedWidth, frame.height)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, frame.width, frame.height)
            if (cropped !== padded) padded.recycle()
            (application as ShareTimeApp).store.prepareBitmap(cropped, expectedRound)
            if (!(application as ShareTimeApp).store.isForeground) {
                Notices.invitation(this, "截图已准备好，点按返回预览并确认发送")
            }
            Toast.makeText(this, "截图完成，返回此刻预览", Toast.LENGTH_LONG).show()
            finishCapture()
        } catch (_: Exception) { fail("截图失败，请重试或选择手机自带截图") }
    }
    private fun fail(text: String) {
        (application as ShareTimeApp).store.message(text)
        finishCapture()
    }
    private fun finishCapture() {
        done = true
        handler.removeCallbacksAndMessages(null)
        latest?.close(); latest = null
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { finishCapture(); super.onDestroy() }
}
