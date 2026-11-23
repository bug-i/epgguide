package de.epgguide

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val DP_PER_MIN = 3f
private const val WINDOW_H = 6
private const val STALE_SECONDS = 6 * 3600L   // erst danach wird das Programm komplett neu geholt
private val fmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val fmtDay = SimpleDateFormat("EE HH:mm", Locale.GERMANY)
private val fmtDate = SimpleDateFormat("EE dd.MM.", Locale.GERMANY)
private val navPad = PaddingValues(horizontal = 8.dp)

private fun dayStart(plusDays: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, plusDays)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private fun nowHalfHour() = System.currentTimeMillis() / 1000 / 1800 * 1800
private fun isMarker(ref: String) = ref.startsWith("1:64:")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun Picon(receiver: Receiver, ch: Channel) {
    val bmp by produceState<Bitmap?>(null, ch.ref, receiver) {
        value = withContext(Dispatchers.IO) { receiver.picon(ch) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(60.dp, 32.dp), contentScale = ContentScale.Fit)
    else Spacer(Modifier.size(60.dp, 32.dp))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }
    val cache = remember { EpgCache(ctx.filesDir) }   // filesDir: wird von Android nicht automatisch geleert
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var bouquets by remember { mutableStateOf<List<Bouquet>>(emptyList()) }
    var bouquet by remember {
        mutableStateOf(prefs.getString("bref", null)?.let { Bouquet(prefs.getString("bname", "") ?: "", it) })
    }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var start by remember { mutableStateOf(nowHalfHour()) }
    var status by remember { mutableStateOf("") }
    var stand by remember { mutableStateOf(0L) }
    var detail by remember { mutableStateOf<Event?>(null) }
    var piconInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(host.isBlank()) }
    var loadKey by remember { mutableStateOf(0) }
    var force by remember { mutableStateOf(false) }

    val rangeStart = remember { dayStart(0) }   // heute 00:00
    val rangeEnd = remember { dayStart(2) }     // Ende von morgen
    val receiver = remember(host, user, pass) { Receiver(host.trim(), user, pass, ctx.cacheDir) }
    val bySref = remember(events) { events.groupBy { it.sref } }

    LaunchedEffect(loadKey) {
        if (host.isBlank()) return@LaunchedEffect
        val doForce = force
        force = false
        val h = host.trim()
        try {
            withContext(Dispatchers.IO) {
                // 1. Sofort aus dem lokalen Cache anzeigen
                var b = bouquet
                var cached = b?.let { cache.load(h, it.ref) }
                if (cached != null) { channels = cached.channels; events = cached.events; stand = cached.ts }

                // 2. Bouquet-Liste (klein und schnell)
                status = "Verbinde …"
                try {
                    if (bouquets.isEmpty()) bouquets = receiver.bouquets()
                } catch (e: Exception) { if (b == null) throw e }
                if (b == null) {
                    b = bouquets.first()
                    bouquet = b
                    cached = cache.load(h, b.ref)
                    if (cached != null) { channels = cached.channels; events = cached.events; stand = cached.ts }
                }
                prefs.edit().putString("bref", b.ref).putString("bname", b.name).apply()

                // 3. Nur nachladen, was wirklich fehlt
                val now = System.currentTimeMillis() / 1000
                val c = cached
                val full = doForce || c == null || now - c.ts > STALE_SECONDS
                val from = if (full || c == null) nowHalfHour() else c.until
                if (full || from < rangeEnd) {
                    status = "Lade Senderliste …"
                    val ch = if (full || c == null) receiver.channels(b.ref) else c.channels
                    channels = ch
                    val merged = HashMap<String, Event>()
                    c?.events?.filter { it.begin < from && it.end > rangeStart }
                        ?.forEach { merged["${it.sref}|${it.begin}"] = it }
                    if (full) {
                        status = "Lade laufendes Programm …"
                        receiver.epgNow(b.ref).forEach { merged["${it.sref}|${it.begin}"] = it }
                        events = merged.values.toList()
                    }
                    val starts = generateSequence(from) { it + WINDOW_H * 3600L }.takeWhile { it < rangeEnd }.toList()
                    coroutineScope {
                        val sem = Semaphore(3)
                        val jobs = starts.map { t ->
                            async { sem.withPermit { receiver.epg(b.ref, t, minOf(t + WINDOW_H * 3600L, rangeEnd)) } }
                        }
                        jobs.forEachIndexed { i, j ->
                            status = "Lade Programm ${i + 1}/${jobs.size} …"
                            j.await().forEach { merged["${it.sref}|${it.begin}"] = it }
                            events = merged.values.toList()
                        }
                    }
                    val ts = if (full) now else (c?.ts ?: now)
                    cache.save(h, b.ref, ts, rangeEnd, ch, merged.values.toList())
                    stand = ts
                }
            }
            status = ""
        } catch (e: Exception) { status = "Fehler: ${e.message}" }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Kompakte Kopfzeile
        Row(Modifier.padding(horizontal = 4.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                TextButton({ open = true }, enabled = bouquets.isNotEmpty(), contentPadding = navPad) {
                    Text((bouquet?.name ?: "Bouquet") + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(open, { open = false }) {
                    bouquets.forEach { b ->
                        DropdownMenuItem({ Text(b.name) }, {
                            bouquet = b; channels = emptyList(); events = emptyList(); stand = 0
                            open = false; loadKey++
                        })
                    }
                }
            }
            TextButton({ start = (start - WINDOW_H * 3600L).coerceIn(rangeStart, rangeEnd - WINDOW_H * 3600L) }, contentPadding = navPad) { Text("◀") }
            TextButton({ start = nowHalfHour() }, contentPadding = navPad) { Text("Jetzt") }
            TextButton({ start = (start + WINDOW_H * 3600L).coerceIn(rangeStart, rangeEnd - WINDOW_H * 3600L) }, contentPadding = navPad) { Text("▶") }
            TextButton({ force = true; loadKey++ }, contentPadding = navPad) { Text("↻") }
            TextButton({ showSettings = true }, contentPadding = navPad) { Text("⚙") }
        }
        Text(
            fmtDate.format(Date(start * 1000)) +
                (if (stand > 0) "  ·  Stand ${fmtDay.format(Date(stand * 1000))}" else "") +
                (if (status.isNotEmpty()) "  ·  $status" else ""),
            Modifier.padding(horizontal = 12.dp), fontSize = 11.sp, color = Color.DarkGray, maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val hScroll = rememberScrollState()
        val gridWidth = (WINDOW_H * 60 * DP_PER_MIN).dp
        Row(Modifier.padding(start = 88.dp)) {
            Row(Modifier.horizontalScroll(hScroll).width(gridWidth)) {
                for (i in 0 until WINDOW_H * 2) {
                    val ts = start + i * 1800L
                    val label = fmt.format(Date(ts * 1000))
                    Text(if (label == "00:00" || i == 0) fmtDay.format(Date(ts * 1000)) else label,
                        fontSize = 11.sp, modifier = Modifier.width((30 * DP_PER_MIN).dp))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            itemsIndexed(channels, key = { i, c -> "$i|${c.ref}" }) { _, ch ->
                if (isMarker(ch.ref)) {
                    // Marker des Bouquets als Trennzeile
                    Box(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFDCE6F2)).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Text(ch.name.ifBlank { "–" }, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Row(Modifier.height(56.dp).border(0.5.dp, Color.LightGray)) {
                        Column(
                            Modifier.width(88.dp)
                                .clickable { scope.launch { piconInfo = withContext(Dispatchers.IO) { receiver.piconDebug(ch) } } }
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Picon(receiver, ch)
                            Text(ch.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.horizontalScroll(hScroll).width(gridWidth)) {
                            var cursor = start
                            val windowEnd = start + WINDOW_H * 3600L
                            (bySref[ch.ref] ?: emptyList()).sortedBy { it.begin }.forEach { e ->
                                val s = maxOf(e.begin, cursor)
                                val en = minOf(e.end, windowEnd)
                                if (en <= s) return@forEach
                                if (s > cursor) Spacer(Modifier.width(((s - cursor) / 60f * DP_PER_MIN).dp))
                                val now = System.currentTimeMillis() / 1000
                                Column(
                                    Modifier.width(((en - s) / 60f * DP_PER_MIN).dp).fillMaxHeight()
                                        .border(0.5.dp, Color.Gray)
                                        .background(if (now in e.begin..e.end) Color(0xFFFFF3C4) else Color(0xFFF1F1F1))
                                        .clickable { detail = e }.padding(3.dp)
                                ) {
                                    Text(e.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(fmt.format(Date(e.begin * 1000)), fontSize = 10.sp, color = Color.DarkGray)
                                }
                                cursor = en
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { e ->
        AlertDialog(onDismissRequest = { detail = null }, confirmButton = { TextButton({ detail = null }) { Text("OK") } },
            title = { Text(e.title) },
            text = { Text("${fmt.format(Date(e.begin * 1000))}–${fmt.format(Date(e.end * 1000))}\n\n${e.desc}") })
    }

    piconInfo?.let { info ->
        AlertDialog(onDismissRequest = { piconInfo = null }, confirmButton = { TextButton({ piconInfo = null }) { Text("OK") } },
            title = { Text("Logo-Diagnose") }, text = { Text(info, fontSize = 12.sp) })
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = {
                TextButton({ showSettings = false; bouquets = emptyList(); loadKey++ }) { Text("Speichern & laden") }
            },
            dismissButton = { TextButton({ showSettings = false }) { Text("Schließen") } },
            title = { Text("Receiver") },
            text = {
                Column {
                    OutlinedTextField(host, { host = it; prefs.edit().putString("host", it).apply() },
                        label = { Text("Receiver-IP") }, singleLine = true)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(user, { user = it; prefs.edit().putString("user", it).apply() },
                        label = { Text("Benutzer") }, singleLine = true)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(pass, { pass = it; prefs.edit().putString("pass", it).apply() },
                        label = { Text("Passwort") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            }
        )
    }
}
