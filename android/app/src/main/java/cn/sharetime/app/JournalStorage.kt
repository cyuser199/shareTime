package cn.sharetime.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class JournalEntry(val id: String, val time: Long, val day: String, val peerName: String, val ordinal: Int = 1)
data class JournalRecord(val entry: JournalEntry, val mine: ByteArray?, val peer: ByteArray)

object JournalDates {
    fun day(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    // Material DatePicker represents a calendar date as midnight UTC, not local midnight.
    fun pickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun fromPicker(millis: Long): LocalDate = day(millis, ZoneOffset.UTC)
    fun ordinal(entries: List<JournalEntry>, day: String): Int = (entries.filter { it.day == day }.maxOfOrNull { it.ordinal } ?: 0) + 1
}

/** Both images and the date index are encrypted; all files are excluded from backup. */
class JournalStorage(context: Context) {
    private val directory = File(context.noBackupFilesDir, "journal").apply { mkdirs() }
    private val index = File(directory, "index.bin")
    private val background = File(directory, "background.bin")
    private val key: SecretKey
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey("sharetime-journal-v1", null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("sharetime-journal-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    private fun write(file: File, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(file.name.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(value)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun read(file: File): ByteArray {
        val bytes = AtomicFile(file).readFully()
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(file.name.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    private fun metadata(entry: JournalEntry) = JSONObject().put("id", entry.id).put("time", entry.time)
        .put("day", entry.day).put("peerName", entry.peerName).put("ordinal", entry.ordinal)
    private fun entry(obj: JSONObject) = JournalEntry(obj.getString("id"), obj.getLong("time"), obj.getString("day"), obj.getString("peerName"), obj.optInt("ordinal", 1))
    private fun readIndex(): JSONObject {
        if (!index.exists() && !File(index.path + ".bak").exists()) return JSONObject().put("entries", JSONArray()).put("counts", JSONObject())
        val text = String(read(index), Charsets.UTF_8)
        return if (text.startsWith("[")) JSONObject().put("entries", JSONArray(text)).put("counts", JSONObject()) else JSONObject(text)
    }
    @Synchronized fun entries(): List<JournalEntry> {
        val array = readIndex().getJSONArray("entries")
        return (0 until array.length()).map { entry(array.getJSONObject(it)) }.sortedByDescending { it.time }
    }
    private fun recordFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(directory, "record-$id.bin")
    }
    @Synchronized fun add(id: String, mine: ByteArray?, peer: ByteArray, name: String): Pair<JournalEntry, Int> {
        val existing = entries()
        existing.firstOrNull { it.id == id }?.let { return it to 0 }
        val time = System.currentTimeMillis()
        val day = JournalDates.day(time).toString()
        val data = readIndex()
        val counts = data.optJSONObject("counts") ?: JSONObject()
        val ordinal = maxOf(counts.optInt(day, 0) + 1, JournalDates.ordinal(existing, day))
        val saved = JournalEntry(id, time, day, name, ordinal)
        val body = metadata(saved).put("mine", mine?.let(Crypto::encode)).put("peer", Crypto.encode(peer))
        write(recordFile(id), body.toString().toByteArray(Charsets.UTF_8))
        val updated = JSONArray().apply { (existing + saved).forEach { put(metadata(it)) } }
        counts.put(day, ordinal)
        write(index, data.put("entries", updated).put("counts", counts).toString().toByteArray(Charsets.UTF_8))
        return saved to ordinal
    }
    @Synchronized fun load(id: String): JournalRecord {
        val obj = JSONObject(String(read(recordFile(id)), Charsets.UTF_8))
        return JournalRecord(entry(obj), obj.optString("mine").takeIf { it.isNotBlank() }?.let(Crypto::decode), Crypto.decode(obj.getString("peer")))
    }
    @Synchronized fun delete(id: String) {
        val updated = JSONArray().apply { entries().filter { it.id != id }.forEach { put(metadata(it)) } }
        write(index, readIndex().put("entries", updated).toString().toByteArray(Charsets.UTF_8))
        AtomicFile(recordFile(id)).delete()
    }
    @Synchronized fun background(): ByteArray? = if (background.exists()) read(background) else null
    @Synchronized fun setBackground(bytes: ByteArray?) {
        if (bytes == null) AtomicFile(background).delete() else write(background, bytes)
    }
}
