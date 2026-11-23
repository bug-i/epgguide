package de.epgguide

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ts = Zeitpunkt des letzten vollständigen Abrufs, until = bis wohin das Programm im Cache reicht. */
data class Cached(val ts: Long, val until: Long, val channels: List<Channel>, val events: List<Event>)

/** Legt Senderliste (inkl. Marker) und Programm je Receiver und Bouquet dauerhaft als JSON ab. */
class EpgCache(private val dir: File) {
    private fun file(host: String, ref: String) = File(dir, "epg2_${(host + ref).hashCode()}.json")

    fun load(host: String, ref: String): Cached? = try {
        val j = JSONObject(file(host, ref).readText())
        if (j.optInt("v") != 2) null else {
            val c = j.getJSONArray("c")
            val e = j.getJSONArray("e")
            Cached(
                j.getLong("ts"), j.getLong("u"),
                (0 until c.length()).map { c.getJSONArray(it).let { a -> Channel(a.getString(0), a.getString(1), a.optString(2, "")) } },
                (0 until e.length()).map {
                    e.getJSONArray(it).let { a -> Event(a.getString(0), a.getString(1), a.getString(2), a.getLong(3), a.getLong(4)) }
                }
            )
        }
    } catch (ex: Exception) { null }

    fun save(host: String, ref: String, ts: Long, until: Long, channels: List<Channel>, events: List<Event>) {
        try {
            val j = JSONObject()
            j.put("v", 2)
            j.put("ts", ts)
            j.put("u", until)
            j.put("c", JSONArray(channels.map { JSONArray(listOf(it.name, it.ref, it.picon)) }))
            j.put("e", JSONArray(events.map { JSONArray(listOf(it.sref, it.title, it.desc, it.begin, it.end)) }))
            val f = file(host, ref)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(j.toString())
            tmp.renameTo(f)
        } catch (ex: Exception) { /* Cache ist optional */ }
    }
}
