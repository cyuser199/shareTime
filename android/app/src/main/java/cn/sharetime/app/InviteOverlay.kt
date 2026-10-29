package cn.sharetime.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ReceiveRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ReceiveService.restore(context)
    }
}

/** Optional, bounded invitation card. Never turns on the screen or bypasses the lock screen. */
@android.annotation.SuppressLint("StaticFieldLeak") // Application context only; card is removed within 15 seconds.
object InviteOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var manager: WindowManager? = null
    private val remove = Runnable { removeNow() }
    private fun removeNow() {
        handler.removeCallbacks(remove)
        view?.let { try { manager?.removeView(it) } catch (_: IllegalArgumentException) { } }
        view = null; manager = null
    }
    fun hide() { handler.post { removeNow() } }
    fun show(source: Context, round: String) { handler.post {
        val context = source.applicationContext
        val app = context as ShareTimeApp
        if (!Settings.canDrawOverlays(context) || app.store.isForeground || app.store.state.value.round != round ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return@post
        removeNow()
        val density = context.resources.displayMetrics.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply { setColor(Color.rgb(235, 247, 237)); cornerRadius = 20 * density }
        }
        card.addView(TextView(context).apply { text = "此刻 · 好友邀请你一起记录"; textSize = 18f; setTextColor(Color.rgb(25, 55, 38)) })
        card.addView(Button(context).apply { text = "查看邀请"; setOnClickListener {
            removeNow()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } })
        card.addView(Button(context).apply { text = "稍后查看"; setOnClickListener { removeNow() } })
        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels - 32 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        try {
            manager = context.getSystemService(WindowManager::class.java)
            manager!!.addView(card, params); view = card
            handler.postDelayed(remove, 15_000)
        } catch (_: RuntimeException) { view = null; manager = null }
    } }
}
