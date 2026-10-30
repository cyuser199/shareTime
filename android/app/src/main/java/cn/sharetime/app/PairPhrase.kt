package cn.sharetime.app

object PairPhrase {
    fun create(code: String, base: String = "https://cyadmin.xyz/sharetime"): String {
        require(code.matches(Regex("[0-9]{8}")))
        return "【此刻好友口令】SHARETIME-$code\n${link(code, base)}\n复制后打开此刻，申请连接好友。配对码固定；登记最长 3 天有效。持有码者可直接配对，请仅分享给信任的好友。"
    }
    fun link(code: String, base: String = "https://cyadmin.xyz/sharetime"): String = "${base.trimEnd('/')}/pair?code=$code"
    fun server(text: String): String? = Regex("(https://[^\\s?#]+)/pair\\?code=[0-9]{8}").find(text.take(2048))?.groupValues?.get(1)
    fun parse(text: String): String? = Regex("(?:^|\\s|】)SHARETIME-([0-9]{8})(?![0-9A-Za-z])").find(text.take(2048))?.groupValues?.get(1) ?: Regex("https://[^\\s?#]+/pair\\?code=([0-9]{8})(?![0-9A-Za-z])").find(text.take(2048))?.groupValues?.get(1)
}
