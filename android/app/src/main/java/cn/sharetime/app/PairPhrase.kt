package cn.sharetime.app

object PairPhrase {
    fun create(code: String): String {
        require(code.matches(Regex("[0-9]{8}")))
        return "【此刻好友口令】SHARETIME-$code\n复制后打开此刻，申请连接好友。口令 5 分钟有效，需好友确认。"
    }
    fun parse(text: String): String? = Regex("(?:^|\\s|】)SHARETIME-([0-9]{8})(?![0-9A-Za-z])").find(text.take(2048))?.groupValues?.get(1)
}
