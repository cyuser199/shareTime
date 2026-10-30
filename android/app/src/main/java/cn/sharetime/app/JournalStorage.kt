package cn.sharetime.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class JournalEntry(val id: String, val time: Long, val day: String, val peerName: String, val ordinal: Int = 1, val mode: String = "duo", val participantNames: List<String> = emptyList(), val photoCount: Int = 0)
data class LocalPhoto(val name: String, val bytes: ByteArray)
data class JournalRecord(val entry: JournalEntry, val mine: ByteArray?, val peer: ByteArray, val others: List<LocalPhoto> = emptyList())

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
    init {
        directory.listFiles()?.filter { it.name.startsWith("pending-record-") && System.currentTimeMillis() - it.lastModified() > 86400_000L }?.forEach { AtomicFile(it).delete() }
    }
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
    private fun transform(cipher: Cipher, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(8192, bytes.size - offset)
            cipher.update(bytes, offset, length)?.let { output.write(it) }
            offset += length
        }
        output.write(cipher.doFinal())
        return output.toByteArray()
    }
    private fun write(file: File, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(file.name.toByteArray())
        val bytes = cipher.iv + transform(cipher, value)
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
        return transform(cipher, bytes.copyOfRange(12, bytes.size))
    }
    private fun metadata(entry: JournalEntry) = JSONObject().put("id", entry.id).put("time", entry.time)
        .put("day", entry.day).put("peerName", entry.peerName).put("ordinal", entry.ordinal).put("mode", entry.mode).put("participantNames", JSONArray(entry.participantNames)).put("photoCount", entry.photoCount)
    private fun entry(obj: JSONObject) = JournalEntry(obj.getString("id"), obj.getLong("time"), obj.getString("day"), obj.getString("peerName"), obj.optInt("ordinal", 1), obj.optString("mode", "duo"),
        obj.optJSONArray("participantNames")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), obj.optInt("photoCount", 0))
    private fun readIndex(): JSONObject {
        if (!index.exists() && !File(index.path + ".bak").exists()) return JSONObject().put("entries", JSONArray()).put("counts", JSONObject())
        val text = String(read(index), Charsets.UTF_8)
        return if (text.startsWith("[")) JSONObject().put("entries", JSONArray(text)).put("counts", JSONObject()) else JSONObject(text)
    }
    @Synchronized fun entries(): List<JournalEntry> {
        val data = readIndex()
        val array = data.getJSONArray("entries")
        var enriched = false
        val result = (0 until array.length()).map {
            val saved = entry(array.getJSONObject(it))
            if (saved.mode == "friends" && saved.participantNames.isEmpty()) {
                try {
                    val body = JSONObject(String(read(recordFile(saved.id)), Charsets.UTF_8))
                    val others = body.optJSONArray("others")
                    enriched = true
                    saved.copy(participantNames = listOf("我", saved.peerName) + (others?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } } ?: emptyList()),
                        photoCount = 1 + (if (body.optString("mine").isNotBlank()) 1 else 0) + (others?.length() ?: 0))
                } catch (_: Exception) { saved }
            } else saved
        }.sortedByDescending { it.time }
        if (enriched) runCatching { write(index, data.put("entries", JSONArray().apply { result.forEach { put(metadata(it)) } }).toString().toByteArray(Charsets.UTF_8)) }
        return result
    }
    private fun recordFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(directory, "record-$id.bin")
    }
    @Synchronized fun savePending(id: String, bytes: ByteArray) {
        val file = recordFile(id).let { File(directory, "pending-${it.name}") }
        write(file, bytes)
        check(read(file).contentEquals(bytes))
    }
    @Synchronized fun pending(id: String): ByteArray? {
        val file = File(directory, "pending-${recordFile(id).name}")
        return if (file.exists()) read(file) else null
    }
    @Synchronized fun discardPending(id: String) {
        if (id.isNotBlank()) AtomicFile(File(directory, "pending-${recordFile(id).name}")).delete()
    }
    @Synchronized fun add(id: String, mine: ByteArray?, peer: ByteArray, name: String, others: List<LocalPhoto> = emptyList(), mode: String = "duo"): Pair<JournalEntry, Int> {
        val existing = entries()
        existing.firstOrNull { it.id == id }?.let { return it to 0 }
        val actualMine = mine ?: pending(id) ?: runCatching { load(id).mine }.getOrNull()
        val time = System.currentTimeMillis()
        val day = JournalDates.day(time).toString()
        val data = readIndex()
        val counts = data.optJSONObject("counts") ?: JSONObject()
        val ordinal = maxOf(counts.optInt(day, 0) + 1, JournalDates.ordinal(existing, day))
        val saved = JournalEntry(id, time, day, name, ordinal, mode, (if (actualMine != null) listOf("我", name) else listOf(name)) + others.map { it.name }, 1 + (if (actualMine != null) 1 else 0) + others.size)
        val body = metadata(saved).put("mine", actualMine?.let(Crypto::encode)).put("peer", Crypto.encode(peer))
            .put("others", JSONArray().apply { others.forEach { put(JSONObject().put("name", it.name).put("bytes", Crypto.encode(it.bytes))) } })
        val encoded = body.toString().toByteArray(Charsets.UTF_8)
        val target = recordFile(id)
        write(target, encoded)
        // Confirm durable ciphertext can be authenticated before indexing or acknowledging it.
        check(read(target).contentEquals(encoded)) { "Journal read-back failed" }
        val updated = JSONArray().apply { (existing + saved).forEach { put(metadata(it)) } }
        counts.put(day, ordinal)
        write(index, data.put("entries", updated).put("counts", counts).toString().toByteArray(Charsets.UTF_8))
        AtomicFile(File(directory, "pending-${recordFile(id).name}")).delete()
        return saved to ordinal
    }
    @Synchronized fun load(id: String): JournalRecord {
        val obj = JSONObject(String(read(recordFile(id)), Charsets.UTF_8))
        return JournalRecord(entry(obj), obj.optString("mine").takeIf { it.isNotBlank() }?.let(Crypto::decode), Crypto.decode(obj.getString("peer")),
            obj.optJSONArray("others")?.let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { LocalPhoto(it.getString("name"), Crypto.decode(it.getString("bytes"))) } } } ?: emptyList())
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
