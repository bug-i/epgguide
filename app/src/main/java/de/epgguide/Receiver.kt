package de.epgguide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Bouquet(val name: String, val ref: String)
data class Channel(val name: String, val ref: String, val picon: String = "")
data class Event(val sref: String, val title: String, val desc: String, val begin: Long, val end: Long)

class Receiver(host: String, private val user: String, private val pass: String, private val cacheDir: File) {
    private val baseUrl = host.trim().trimEnd('/').let {
        when {
            it.startsWith("http://", true) || it.startsWith("https://", true) -> it
            else -> "http://$it"
        }
    }
    private val receiverKey = baseUrl.lowercase(Locale.ROOT).hashCode().toString()

    companion object {
        private val memory = LruCache<String, Bitmap>(300)
        private val missing = ConcurrentHashMap.newKeySet<String>()
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun open(path: String): HttpURLConnection {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        if (user.isNotBlank()) {
            connection.setRequestProperty(
                "Authorization",
                "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            )
        }
        return connection
    }

    private fun get(path: String): JSONObject {
        val connection = open(path)
        try {
            val code = connection.responseCode
            if (code == 401) throw Exception("Anmeldung fehlgeschlagen (Benutzer/Passwort prüfen)")
            if (code !in 200..299) throw Exception("Receiver antwortet mit HTTP $code")
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    fun bouquets(): List<Bouquet> {
        val array = get("/api/getservices").getJSONArray("services")
        return (0 until array.length()).map { array.getJSONObject(it) }
            .map { Bouquet(it.getString("servicename"), it.getString("servicereference")) }
    }

    fun channels(bouquetRef: String): List<Channel> {
        val array = get("/api/getservices?sRef=${enc(bouquetRef)}").getJSONArray("services")
        return (0 until array.length()).map { array.getJSONObject(it) }
            .map { Channel(it.getString("servicename"), it.getString("servicereference"), it.optString("picon", "")) }
    }

    private fun parseEvents(array: JSONArray): List<Event> =
        (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull {
            val begin = it.optLong("begin_timestamp", 0)
            val duration = it.optLong("duration_sec", 0)
            val title = it.optString("title", "")
            val serviceRef = it.optString("sref", "")
            if (begin <= 0 || duration <= 0 || title.isBlank() || serviceRef.isBlank()) null
            else Event(serviceRef, title, it.optString("longdesc").ifBlank { it.optString("shortdesc") }, begin, begin + duration)
        }

    fun epg(bouquetRef: String, from: Long, to: Long): List<Event> =
        parseEvents(get("/api/epgmulti?bRef=${enc(bouquetRef)}&time=$from&endTime=$to").getJSONArray("events"))

    fun epgNow(bouquetRef: String): List<Event> =
        parseEvents(get("/api/epgnow?bRef=${enc(bouquetRef)}").getJSONArray("events"))

    private fun normalizeName(name: String): String {
        val normalized = java.text.Normalizer.normalize(
            name.replace("&", "and").replace("+", "plus").replace("*", "star"),
            java.text.Normalizer.Form.NFKD
        )
        return normalized.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
    }

    private fun piconCandidates(channel: Channel): List<String> {
        val result = LinkedHashSet<String>()
        if (channel.picon.startsWith("/") && !channel.picon.contains("default", true)) result.add(channel.picon)
        val fields = channel.ref.split(":").toMutableList()
        if (fields.size >= 10) {
            fun path() = "/picon/" + fields.take(10).joinToString("_") + ".png"
            result.add(path())
            if (fields[6].length > 4 && !fields[6].endsWith("0000")) {
                fields[6] = fields[6].dropLast(4) + "0000"
                result.add(path())
            }
            if (fields[2] != "2") {
                fields[2] = "1"
                result.add(path())
            }
        }
        result.add("/picon/${normalizeName(channel.name)}.png")
        return result.toList()
    }

    fun picon(channel: Channel): Bitmap? {
        val fileName = channel.ref.trimEnd(':').replace(':', '_') + ".png"
        val key = "${receiverKey}_$fileName"
        memory.get(key)?.let { return it }
        if (key in missing) return null
        val directory = File(cacheDir, "picons_$receiverKey").apply { mkdirs() }
        val imageFile = File(directory, fileName)
        val noneFile = File(directory, "$fileName.none")
        if (imageFile.exists()) {
            BitmapFactory.decodeFile(imageFile.path)?.let { memory.put(key, it); return it }
            imageFile.delete()
        }
        if (noneFile.exists() && System.currentTimeMillis() - noneFile.lastModified() < 7L * 24 * 3600 * 1000) {
            missing.add(key)
            return null
        }
        var networkFailed = false
        for (path in piconCandidates(channel)) {
            val connection = try { open(path) } catch (_: Exception) { networkFailed = true; continue }
            try {
                if (connection.responseCode != 200) continue
                val bytes = connection.inputStream.use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                imageFile.writeBytes(bytes)
                memory.put(key, bitmap)
                return bitmap
            } catch (_: Exception) {
                networkFailed = true
            } finally {
                connection.disconnect()
            }
        }
        if (!networkFailed) runCatching { noneFile.writeText("") }
        missing.add(key)
        return null
    }
}
