package de.epgguide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class Bouquet(val name: String, val ref: String)
data class Channel(val name: String, val ref: String)
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
            .filter { !it.getString("servicereference").startsWith("1:64:") } // Marker raus
            .map { Channel(it.getString("servicename"), it.getString("servicereference")) }
    }

    /** Sendungen des Bouquets im Zeitfenster [from, to] (Unix-Sekunden). */
    fun epg(bouquetRef: String, from: Long, to: Long): List<Event> {
        val a = get("/api/epgmulti?bRef=${enc(bouquetRef)}&time=$from&endTime=$to").getJSONArray("events")
        return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull {
            val begin = it.optLong("begin_timestamp", 0)
            val dur = it.optLong("duration_sec", 0)
            val title = it.optString("title", "")
            if (begin == 0L || title.isBlank()) null
            else Event(it.optString("sref"), title,
                it.optString("longdesc").ifBlank { it.optString("shortdesc") }, begin, begin + dur)
        }
    }

    /** Senderlogo (Picon): erst Speicher, dann Platte, dann Receiver. */
    fun picon(ref: String): Bitmap? {
        val name = ref.trimEnd(':').replace(':', '_') + ".png"
        memory.get(name)?.let { return it }
        if (name in missing) return null
        val f = File(File(cacheDir, "picons").apply { mkdirs() }, name)
        return try {
            if (!f.exists()) {
                val c = open("/picon/$name")
                if (c.responseCode != 200) { missing.add(name); return null }
                f.outputStream().use { o -> c.inputStream.use { it.copyTo(o) } }
            }
            BitmapFactory.decodeFile(f.path)?.also { memory.put(name, it) }
        } catch (e: Exception) { f.delete(); null }
    }
}
