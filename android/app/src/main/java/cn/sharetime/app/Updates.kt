package cn.sharetime.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdate(val code: Int, val version: String, val notes: String, val url: String, val sha256: String)

object Updates {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    suspend fun check(base: String): AppUpdate? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("${base.trimEnd('/')}/updates/latest").build()).execute().use { response ->
            check(response.isSuccessful) { "更新服务暂时不可用" }
            val json = JSONObject(response.body!!.string())
            val code = json.getInt("versionCode")
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            val url = json.getString("apkUrl")
            require(url.startsWith("https://"))
            val hash = json.getString("sha256")
            require(hash.matches(Regex("[a-f0-9]{64}")))
            AppUpdate(code, json.getString("versionName"), json.optString("notes"), url, hash)
        }
    }
}
