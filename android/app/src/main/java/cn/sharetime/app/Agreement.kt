package cn.sharetime.app

import android.content.Context

object Agreement {
    const val VERSION = 1
    fun accepted(context: Context) = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getInt("agreement", 0) == VERSION
    fun accept(context: Context) { context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putInt("agreement", VERSION).apply() }
    val text = """
        此刻 · 使用协议与隐私说明（草案 v1）
        更新日期：2026 年 10 月 29 日

        本软件由CY个人开发，用于两位好友自愿分享生活记录。

        1. 自愿使用
        请仅与信任的好友配对。每次邀请均由接收方自主同意或拒绝；拍照、截图和提交都需要你的操作。不要拍摄或传播侵犯他人隐私、肖像、著作权或违法的内容。

        2. 数据与保存
        昵称、设备凭据和配对信息用于连接服务。记录图片端到端加密传输，服务器仅在内存暂存密文，双方确认接收、超时或服务重启后清理，不将图片保存到服务器磁盘。服务器持久保存累计注册设备数及服务必需密钥，该数量不等同于实际人数。
        完成的双方记录和自选背景加密保存在各自手机。可删除本机时间轴记录；卸载或清除应用数据可能永久丢失记录。保存到相册的副本受相册及其备份设置管理。删除本机内容不会删除好友或相册里的副本，请在分享前考虑清楚。

        3. 邀请与权限
        离线邀请在服务器内存最多等待 24 小时，重启后不保留；同意后的记录限时 10 分钟。通知、后台接收和悬浮窗均可自行选择与关闭。悬浮窗仅用于邀请提醒，不读取其他应用内容；截图只在系统授权后截取一次。拒绝额外权限仍可在应用内操作。

        4. 服务边界
        这是个人开发的测试软件，不承诺全天候在线或通知必达。断网、关机、强制停止、系统省电或服务器停机均可能造成延迟或内容丢失，请自行保存重要记录。当前版本通过互联网连接云服务器，请保持网络可用。

        5. 你的选择
        不同意可退出；同意后可在设置中再次查看本说明，关闭后台接收、撤销系统权限、解除配对、删除记录或卸载软件。涉及你的法定权利，按适用法律处理，本协议不排除依法不可排除的责任。
    """.trimIndent()
}
