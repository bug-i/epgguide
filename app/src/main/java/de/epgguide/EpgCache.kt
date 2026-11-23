package de.epgguide

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Cached(val ts: Long, val channels: List<Channel>, val events: List<Event>)

/** Legt Senderliste und Programm je Bouquet als JSON im Cache-Verzeichnis ab. */
class EpgCache(private val dir: File) {
    private fun file(ref: String) = File(dir, "epg_${ref.hashCode()}.json")

    fun load(ref: String): Cached? = try {
        val j = JSONObject(file(ref).readText())
        val c = j.getJSONArray("c")
        val e = j.getJSONArray("e")
        Cached(
            j.getLong("ts"),
            (0 until c.length()).map { c.getJSONArray(it).let { a -> Channel(a.getString(0), a.getString(1)) } },
            (0 until e.length()).map {
                e.getJSONArray(it).let { a -> Event(a.getString(0), a.getString(1), a.getString(2), a.getLong(3), a.getLong(4)) }
            }
        )
    } catch (ex: Exception) { null }

    fun save(ref: String, ts: Long, channels: List<Channel>, events: List<Event>) {
        val j = JSONObject()
        j.put("ts", ts)
        j.put("c", JSONArray(channels.map { JSONArray(listOf(it.name, it.ref)) }))
        j.put("e", JSONArray(events.map { JSONArray(listOf(it.sref, it.title, it.desc, it.begin, it.end)) }))
        file(ref).writeText(j.toString())
    }
}
