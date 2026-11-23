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

/** Spricht die OpenWebif-API des Receivers an (Port 80, optional Basic-Auth). */
class Receiver(
    private val host: String,
    private val user: String,
    private val pass: String,
    private val cacheDir: File
) {
    companion object {
        private val memory = LruCache<String, Bitmap>(300)
        private val missing = ConcurrentHashMap.newKeySet<String>()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(path: String): HttpURLConnection {
        val c = URL("http://$host$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 60000
        if (user.isNotBlank())
            c.setRequestProperty("Authorization",
                "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP))
        if (c.responseCode == 401) throw Exception("Anmeldung fehlgeschlagen (Benutzer/Passwort prüfen)")
        return c
    }

    private fun get(path: String): JSONObject =
        open(path).inputStream.bufferedReader().use { JSONObject(it.readText()) }

    fun bouquets(): List<Bouquet> {
        val a = get("/api/getservices").getJSONArray("services")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Bouquet(it.getString("servicename"), it.getString("servicereference")) }
    }

    fun channels(bouquetRef: String): List<Channel> {
        val a = get("/api/getservices?sRef=${enc(bouquetRef)}").getJSONArray("services")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Channel(it.getString("servicename"), it.getString("servicereference"), it.optString("picon", "")) }
    }

    private fun parseEvents(a: JSONArray): List<Event> =
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull {
            val begin = it.optLong("begin_timestamp", 0)
            val dur = it.optLong("duration_sec", 0)
            val title = it.optString("title", "")
            val sref = it.optString("sref", "")
            if (begin == 0L || title.isBlank() || sref.isBlank()) null
            else Event(sref, title,
                it.optString("longdesc").ifBlank { it.optString("shortdesc") }, begin, begin + dur)
        }

    /** Sendungen des Bouquets im Zeitfenster [from, to] (Unix-Sekunden). */
    fun epg(bouquetRef: String, from: Long, to: Long): List<Event> =
        parseEvents(get("/api/epgmulti?bRef=${enc(bouquetRef)}&time=$from&endTime=$to").getJSONArray("events"))

    /** Aktuell laufende Sendung jedes Senders (schnell, deckt Sendungen ab, die schon begonnen haben). */
    fun epgNow(bouquetRef: String): List<Event> =
        parseEvents(get("/api/epgnow?bRef=${enc(bouquetRef)}").getJSONArray("events"))

    /** Mögliche Picon-Pfade in der Reihenfolge, wie Enigma2 sie selbst probiert (Referenz, Namensvarianten, Sendername). */
    private fun piconCandidates(ch: Channel): List<String> {
        val out = LinkedHashSet<String>()
        if (ch.picon.startsWith("/") && !ch.picon.contains("default", ignoreCase = true)) out.add(ch.picon)
        val f = ch.ref.split(":").toMutableList()
        if (f.size >= 10) {
            fun n() = "/picon/" + f.take(10).joinToString("_") + ".png"
            out.add(n())
            if (f[6].length > 4 && !f[6].endsWith("0000")) { f[6] = f[6].dropLast(4) + "0000"; out.add(n()) }
            if (f[2] != "2") { f[2] = "1"; out.add(n()) }
        }
        out.add("/picon/" + normalizeName(ch.name) + ".png")
        return out.toList()
    }

    private fun normalizeName(name: String): String {
        val s = java.text.Normalizer.normalize(
            name.replace("&", "and").replace("+", "plus").replace("*", "star"),
            java.text.Normalizer.Form.NFKD
        )
        return s.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
    }

    /** Senderlogo (Picon): erst Speicher, dann Platte, dann Receiver (mehrere Namensvarianten). */
    fun picon(ch: Channel): Bitmap? {
        val key = ch.ref.trimEnd(':').replace(':', '_') + ".png"
        memory.get(key)?.let { return it }
        if (key in missing) return null
        val dir = File(cacheDir, "picons").apply { mkdirs() }
        val f = File(dir, key)
        val none = File(dir, "$key.none")
        if (f.exists()) {
            BitmapFactory.decodeFile(f.path)?.let { memory.put(key, it); return it }
            f.delete()
        }
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 24 * 3600 * 1000L) {
            missing.add(key); return null
        }
        var failed = false
        for (path in piconCandidates(ch)) {
            try {
                val c = open(path)
                if (c.responseCode != 200) continue
                val bytes = c.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                f.writeBytes(bytes)
                memory.put(key, bmp)
                return bmp
            } catch (e: Exception) { failed = true }
        }
        if (!failed) { try { none.writeText("") } catch (e: Exception) { } }
        missing.add(key)
        return null
    }

    /** Diagnose: probiert alle Picon-Pfade frisch durch und beschreibt das Ergebnis. */
    fun piconDebug(ch: Channel): String {
        val sb = StringBuilder("Referenz: ${ch.ref}\nPicon-Feld: '${ch.picon}'\n\n")
        for (path in piconCandidates(ch)) {
            sb.append(path).append("\n  → ")
            try {
                val c = open(path)
                val code = c.responseCode
                if (code != 200) { sb.append("HTTP $code\n"); continue }
                val bytes = c.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                sb.append(if (bmp != null) "OK ${bmp.width}x${bmp.height}\n" else "kein Bild (${bytes.size} Bytes)\n")
            } catch (e: Exception) { sb.append("Fehler: ${e.message}\n") }
        }
        return sb.toString()
    }
}
