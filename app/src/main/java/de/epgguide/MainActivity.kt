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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val DP_PER_MIN = 3f
private const val WINDOW_H = 6
private const val STALE_SECONDS = 3600L
private val fmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val fmtDay = SimpleDateFormat("EE HH:mm", Locale.GERMANY)
private val fmtDate = SimpleDateFormat("EE dd.MM.", Locale.GERMANY)

private fun dayStart(plusDays: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, plusDays)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private fun nowHalfHour() = System.currentTimeMillis() / 1000 / 1800 * 1800

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun Picon(receiver: Receiver, ref: String) {
    val bmp by produceState<Bitmap?>(null, ref, receiver) {
        value = withContext(Dispatchers.IO) { receiver.picon(ref) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(60.dp, 32.dp), contentScale = ContentScale.Fit)
    else Spacer(Modifier.size(60.dp, 32.dp))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }
    val cache = remember { EpgCache(ctx.cacheDir) }
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
    var detail by remember { mutableStateOf<Event?>(null) }
    var loadKey by remember { mutableStateOf(0) }
    var force by remember { mutableStateOf(false) }

    val rangeStart = remember { dayStart(0) }   // heute 00:00
    val rangeEnd = remember { dayStart(2) }     // übermorgen 00:00 = Ende von morgen
    val receiver = remember(host, user, pass) { Receiver(host.trim(), user, pass, ctx.cacheDir) }
    val bySref = remember(events) { events.groupBy { it.sref } }

    LaunchedEffect(loadKey) {
        if (host.isBlank()) return@LaunchedEffect
        val doForce = force
        force = false
        try {
            status = "Lade …"
            withContext(Dispatchers.IO) {
                if (bouquets.isEmpty()) {
                    try { bouquets = receiver.bouquets() } catch (e: Exception) { if (bouquet == null) throw e }
                }
                val b = bouquet ?: bouquets.first().also { bouquet = it }
                prefs.edit().putString("bref", b.ref).putString("bname", b.name).apply()

                val cached = cache.load(b.ref)
                if (cached != null) { channels = cached.channels; events = cached.events }

                val now = System.currentTimeMillis() / 1000
                if (doForce || cached == null || now - cached.ts > STALE_SECONDS) {
                    channels = receiver.channels(b.ref)
                    val from = nowHalfHour()
                    // alte Sendungen vor "from" behalten, alles Spätere wird neu geholt
                    val merged = HashMap<String, Event>()
                    cached?.events?.filter { it.begin < from && it.end > rangeStart }
                        ?.forEach { merged["${it.sref}|${it.begin}"] = it }
                    var t = from
                    val chunks = ((rangeEnd - from) / (WINDOW_H * 3600L)).toInt() + 1
                    var n = 0
                    while (t < rangeEnd) {
                        status = "Lade Programm ${++n}/$chunks …"
                        val e = minOf(t + WINDOW_H * 3600L, rangeEnd)
                        receiver.epg(b.ref, t, e).forEach { merged["${it.sref}|${it.begin}"] = it }
                        events = merged.values.toList()
                        t = e
                    }
                    cache.save(b.ref, now, channels, merged.values.toList())
                }
            }
            status = ""
        } catch (e: Exception) { status = "Fehler: ${e.message}" }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(host, { host = it }, label = { Text("Receiver-IP") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(user, { user = it }, label = { Text("Benutzer") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(pass, { pass = it }, label = { Text("Passwort") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            Button({
                prefs.edit().putString("host", host).putString("user", user).putString("pass", pass).apply()
                bouquets = emptyList(); force = true; loadKey++
            }) { Text("Laden") }
        }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ open = true }, enabled = bouquets.isNotEmpty()) { Text(bouquet?.name ?: "Bouquet") }
                DropdownMenu(open, { open = false }) {
                    bouquets.forEach { b ->
                        DropdownMenuItem({ Text(b.name) }, {
                            bouquet = b; channels = emptyList(); events = emptyList()
                            open = false; loadKey++
                        })
                    }
                }
            }
            TextButton({ start = (start - WINDOW_H * 3600L).coerceIn(rangeStart, rangeEnd - WINDOW_H * 3600L) }) { Text("−${WINDOW_H}h") }
            TextButton({ start = nowHalfHour() }) { Text("Jetzt") }
            TextButton({ start = (start + WINDOW_H * 3600L).coerceIn(rangeStart, rangeEnd - WINDOW_H * 3600L) }) { Text("+${WINDOW_H}h") }
            TextButton({ force = true; loadKey++ }) { Text("↻") }
        }
        Text(fmtDate.format(Date(start * 1000)) + if (status.isNotEmpty()) "   $status" else "",
            Modifier.padding(horizontal = 12.dp), fontSize = 12.sp)

        val hScroll = rememberScrollState()
        val gridWidth = (WINDOW_H * 60 * DP_PER_MIN).dp
        Row(Modifier.padding(start = 88.dp)) {
            Row(Modifier.horizontalScroll(hScroll).width(gridWidth)) {
                for (i in 0 until WINDOW_H * 2) {
                    val ts = start + i * 1800L
                    val midnight = Date(ts * 1000).let { fmt.format(it) == "00:00" }
                    Text(if (midnight || i == 0) fmtDay.format(Date(ts * 1000)) else fmt.format(Date(ts * 1000)),
                        fontSize = 11.sp, modifier = Modifier.width((30 * DP_PER_MIN).dp))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(channels, key = { it.ref }) { ch ->
                Row(Modifier.height(56.dp).border(0.5.dp, Color.LightGray)) {
                    Column(Modifier.width(88.dp).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Picon(receiver, ch.ref)
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
    detail?.let { e ->
        AlertDialog(onDismissRequest = { detail = null }, confirmButton = { TextButton({ detail = null }) { Text("OK") } },
            title = { Text(e.title) },
            text = { Text("${fmt.format(Date(e.begin * 1000))}–${fmt.format(Date(e.end * 1000))}\n\n${e.desc}") })
    }
}
