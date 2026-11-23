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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

private const val HOURS = 24
private const val STALE_SECONDS = 6 * 3600L
private val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val dateFmt = SimpleDateFormat("EE dd.MM.", Locale.GERMANY)

private enum class UiSize { COMPACT, NORMAL, LARGE }

private fun dayStart(offset: Int = 0): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, offset)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private fun moveDay(timestamp: Long, amount: Int): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp * 1000
    add(Calendar.DAY_OF_YEAR, amount)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private fun nextDay(timestamp: Long) = moveDay(timestamp, 1)

private fun primeTime(day: Long): Long = Calendar.getInstance().apply {
    timeInMillis = day * 1000
    set(Calendar.HOUR_OF_DAY, 20)
    set(Calendar.MINUTE, 15)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private fun isMarker(ref: String) = ref.startsWith("1:64:")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
private fun Picon(receiver: Receiver, channel: Channel, size: UiSize) {
    val bitmap by produceState<Bitmap?>(null, channel.ref, receiver) {
        value = withContext(Dispatchers.IO) { receiver.picon(channel) }
    }
    val width = when (size) {
        UiSize.COMPACT -> 48.dp
        UiSize.NORMAL -> 60.dp
        UiSize.LARGE -> 72.dp
    }
    val height = when (size) {
        UiSize.COMPACT -> 26.dp
        UiSize.NORMAL -> 32.dp
        UiSize.LARGE -> 40.dp
    }
    bitmap?.let {
        Image(it.asImageBitmap(), channel.name, Modifier.size(width, height), contentScale = ContentScale.Fit)
    } ?: Spacer(Modifier.size(width, height))
}

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("p", Context.MODE_PRIVATE) }
    val cache = remember { EpgCache(context.filesDir) }

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var bouquets by remember { mutableStateOf<List<Bouquet>>(emptyList()) }
    var bouquet by remember {
        mutableStateOf(prefs.getString("bref", null)?.let {
            Bouquet(prefs.getString("bname", "") ?: "", it)
        })
    }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var selectedDay by remember { mutableLongStateOf(dayStart()) }
    var scrollTarget by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var status by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Event?>(null) }
    var dayChannel by remember { mutableStateOf<Channel?>(null) }
    var showSettings by remember { mutableStateOf(host.isBlank()) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var loadKey by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    var bouquetMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var uiSize by remember {
        mutableStateOf(
            runCatching { UiSize.valueOf(prefs.getString("uiSize", "NORMAL") ?: "NORMAL") }
                .getOrDefault(UiSize.NORMAL)
        )
    }

    val loadedDays = remember { mutableStateMapOf<Long, Boolean>() }
    val receiver = remember(host, user, pass) { Receiver(host.trim(), user, pass, context.cacheDir) }
    val bySref = remember(events) { events.groupBy { it.sref } }
    val channelByRef = remember(channels) { channels.associateBy { it.ref } }

    fun requestDay(day: Long) {
        selectedDay = day
        scrollTarget = day
        loadKey++
    }

    fun jumpToNow() {
        selectedDay = dayStart()
        scrollTarget = System.currentTimeMillis() / 1000
        loadKey++
    }

    fun jumpToPrimeTime() {
        scrollTarget = primeTime(selectedDay)
    }

    LaunchedEffect(loadKey, bouquet?.ref) {
        if (host.isBlank()) return@LaunchedEffect
        try {
            status = "Verbinde …"
            if (bouquets.isEmpty()) bouquets = withContext(Dispatchers.IO) { receiver.bouquets() }
            var selectedBouquet = bouquet
            if (selectedBouquet == null) {
                selectedBouquet = bouquets.firstOrNull() ?: error("Keine Bouquets gefunden")
                bouquet = selectedBouquet
            }
            prefs.edit().putString("bref", selectedBouquet.ref).putString("bname", selectedBouquet.name).apply()

            val cached = withContext(Dispatchers.IO) { cache.load(host.trim(), selectedBouquet.ref) }
            if (channels.isEmpty() && cached != null) {
                channels = cached.channels
                events = cached.events
            }
            if (channels.isEmpty() || force) {
                channels = withContext(Dispatchers.IO) { receiver.channels(selectedBouquet.ref) }
            }

            val day = selectedDay
            val stale = cached == null || System.currentTimeMillis() / 1000 - cached.ts > STALE_SECONDS
            if (force || loadedDays[day] != true || (stale && day == dayStart())) {
                status = "Aktualisiere ${dateFmt.format(Date(day * 1000))} …"
                val fresh = withContext(Dispatchers.IO) {
                    val list = receiver.epg(selectedBouquet.ref, day, nextDay(day))
                    if (day == dayStart()) list + receiver.epgNow(selectedBouquet.ref) else list
                }
                val merged = LinkedHashMap<String, Event>()
                events.forEach { merged["${it.sref}|${it.begin}"] = it }
                fresh.forEach { merged["${it.sref}|${it.begin}"] = it }
                events = merged.values.toList()
                loadedDays[day] = true
                cache.save(
                    host.trim(),
                    selectedBouquet.ref,
                    System.currentTimeMillis() / 1000,
                    merged.values.maxOfOrNull { it.end } ?: nextDay(day),
                    channels,
                    events
                )
            }
            force = false
            status = ""
        } catch (e: Exception) {
            status = "Fehler: ${e.message}"
            force = false
        }
    }

    val rowHeight = when (uiSize) {
        UiSize.COMPACT -> 48.dp
        UiSize.NORMAL -> 60.dp
        UiSize.LARGE -> 74.dp
    }
    val logoWidth = when (uiSize) {
        UiSize.COMPACT -> 76.dp
        UiSize.NORMAL -> 92.dp
        UiSize.LARGE -> 110.dp
    }
    val dpPerMin = when (uiSize) {
        UiSize.COMPACT -> 1.4f
        UiSize.NORMAL -> 2.0f
        UiSize.LARGE -> 2.7f
    }
    val titleSize = when (uiSize) {
        UiSize.COMPACT -> 11.sp
        UiSize.NORMAL -> 13.sp
        UiSize.LARGE -> 16.sp
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { jumpToNow() }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("Jetzt")
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { jumpToPrimeTime() }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("20:15")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSearch = true }) { Text("🔍") }
            TextButton(onClick = {
                force = true
                loadedDays.remove(selectedDay)
                loadKey++
            }) { Text("↻") }
            TextButton(onClick = { showSettings = true }) { Text("⚙") }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { requestDay(moveDay(selectedDay, -1)) }) { Text("◀") }
            for (offset in 0..6) {
                val day = dayStart(offset)
                FilterChip(
                    selected = selectedDay == day,
                    onClick = { requestDay(day) },
                    label = { Text(if (offset == 0) "Heute" else dateFmt.format(Date(day * 1000))) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            TextButton(onClick = { requestDay(moveDay(selectedDay, 1)) }) { Text("▶") }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        EpgGrid(
            channels = channels,
            bySref = bySref,
            day = selectedDay,
            scrollTarget = scrollTarget,
            receiver = receiver,
            size = uiSize,
            rowHeight = rowHeight,
            logoWidth = logoWidth,
            dpPerMin = dpPerMin,
            titleSize = titleSize,
            openDay = { dayChannel = it },
            openEvent = { detail = it }
        )
    }

    detail?.let { event ->
        val durationMinutes = ((event.end - event.begin) / 60).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("OK") } },
            title = { Text(event.title) },
            text = {
                Text(
                    "${timeFmt.format(Date(event.begin * 1000))}–${timeFmt.format(Date(event.end * 1000))}" +
                        "  ·  $durationMinutes Min.\n\n${event.desc}"
                )
            }
        )
    }

    dayChannel?.let { channel ->
        val list = bySref[channel.ref].orEmpty()
            .filter { it.begin < nextDay(selectedDay) && it.end > selectedDay }
            .sortedBy { it.begin }
        AlertDialog(
            onDismissRequest = { dayChannel = null },
            confirmButton = { TextButton(onClick = { dayChannel = null }) { Text("Schließen") } },
            title = { Text("${channel.name} · ${dateFmt.format(Date(selectedDay * 1000))}") },
            text = {
                if (list.isEmpty()) {
                    Text("Keine Programmdaten für diesen Tag.")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                        items(list, key = { "${it.sref}|${it.begin}" }) { event ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    dayChannel = null
                                    detail = event
                                }.padding(8.dp)
                            ) {
                                Text(
                                    "${timeFmt.format(Date(event.begin * 1000))}–${timeFmt.format(Date(event.end * 1000))}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(event.title)
                                if (event.desc.isNotBlank()) {
                                    Text(event.desc, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        )
    }

    if (showSearch) {
        val results = events.filter {
            query.length >= 2 && (it.title.contains(query, true) || it.desc.contains(query, true))
        }.sortedBy { it.begin }.take(200)
        AlertDialog(
            onDismissRequest = { showSearch = false },
            confirmButton = { TextButton(onClick = { showSearch = false }) { Text("Schließen") } },
            title = { Text("Sendung suchen") },
            text = {
                Column {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Titel oder Beschreibung") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(results, key = { "${it.sref}|${it.begin}" }) { event ->
                            val sender = channelByRef[event.sref]?.name ?: "Unbekannter Sender"
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    showSearch = false
                                    selectedDay = Calendar.getInstance().apply {
                                        timeInMillis = event.begin * 1000
                                        set(Calendar.HOUR_OF_DAY, 0)
                                        set(Calendar.MINUTE, 0)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis / 1000
                                    scrollTarget = event.begin
                                    detail = event
                                    loadKey++
                                }.padding(8.dp)
                            ) {
                                Text(event.title, fontWeight = FontWeight.Bold)
                                Text(
                                    "$sender · ${dateFmt.format(Date(event.begin * 1000))} " +
                                        "${timeFmt.format(Date(event.begin * 1000))}–${timeFmt.format(Date(event.end * 1000))}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit()
                        .putString("host", host)
                        .putString("user", user)
                        .putString("pass", pass)
                        .putString("uiSize", uiSize.name)
                        .apply()
                    channels = emptyList()
                    events = emptyList()
                    loadedDays.clear()
                    showSettings = false
                    force = true
                    loadKey++
                }) { Text("Speichern & laden") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Schließen") } },
            title = { Text("Einstellungen") },
            text = {
                Column {
                    OutlinedTextField(host, { host = it }, label = { Text("Receiver-IP") }, singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("Benutzer") }, singleLine = true)
                    OutlinedTextField(
                        pass,
                        { pass = it },
                        label = { Text("Passwort") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedButton(onClick = { bouquetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(bouquet?.name ?: "Bouquet auswählen", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(bouquetMenu, { bouquetMenu = false }) {
                            bouquets.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.name) },
                                    onClick = {
                                        bouquet = item
                                        channels = emptyList()
                                        events = emptyList()
                                        loadedDays.clear()
                                        prefs.edit().putString("bref", item.ref).putString("bname", item.name).apply()
                                        bouquetMenu = false
                                        force = true
                                        loadKey++
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { sizeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Darstellung: ${when (uiSize) {
                                    UiSize.COMPACT -> "Kompakt"
                                    UiSize.NORMAL -> "Normal"
                                    UiSize.LARGE -> "Groß"
                                }}"
                            )
                        }
                        DropdownMenu(sizeMenu, { sizeMenu = false }) {
                            UiSize.values().forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (item) {
                                                UiSize.COMPACT -> "Kompakt"
                                                UiSize.NORMAL -> "Normal"
                                                UiSize.LARGE -> "Groß"
                                            }
                                        )
                                    },
                                    onClick = { uiSize = item; sizeMenu = false }
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun EpgGrid(
    channels: List<Channel>,
    bySref: Map<String, List<Event>>,
    day: Long,
    scrollTarget: Long,
    receiver: Receiver,
    size: UiSize,
    rowHeight: Dp,
    logoWidth: Dp,
    dpPerMin: Float,
    titleSize: TextUnit,
    openDay: (Channel) -> Unit,
    openEvent: (Event) -> Unit
) {
    val horizontalScroll = rememberScrollState()
    val gridWidth = (HOURS * 60 * dpPerMin).dp
    val today = day == dayStart()
    val now = System.currentTimeMillis() / 1000
    val nowOffset = ((now - day) / 60f * dpPerMin).coerceIn(0f, HOURS * 60 * dpPerMin)

    LaunchedEffect(scrollTarget, day, dpPerMin) {
        // 90 Minuten Kontext links vom Ziel lassen.
        val target = (((scrollTarget - day) / 60f - 90f) * dpPerMin).toInt().coerceAtLeast(0)
        horizontalScroll.scrollTo(target)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            Box(
                Modifier.width(logoWidth).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .zIndex(2f),
                contentAlignment = Alignment.Center
            ) {
                Text(dateFmt.format(Date(day * 1000)), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalScroll)) {
                Row(Modifier.width(gridWidth).fillMaxHeight()) {
                    for (index in 0 until 48) {
                        Text(
                            timeFmt.format(Date((day + index * 1800L) * 1000)),
                            Modifier.width((30 * dpPerMin).dp).padding(start = 2.dp),
                            fontSize = 11.sp
                        )
                    }
                }
                if (today) {
                    Box(
                        Modifier.offset(x = nowOffset.dp).width(2.dp).fillMaxHeight()
                            .background(Color.Red).zIndex(3f)
                    )
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            itemsIndexed(channels, key = { index, channel -> "$index|${channel.ref}" }) { _, channel ->
                if (isMarker(channel.ref)) {
                    Box(
                        Modifier.fillMaxWidth().height(30.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            channel.name.ifBlank { "–" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth().height(rowHeight)) {
                        Column(
                            Modifier.width(logoWidth).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface)
                                .border(.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .clickable { openDay(channel) }
                                .padding(2.dp)
                                .zIndex(2f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Picon(receiver, channel, size)
                            Text(
                                channel.name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalScroll)) {
                            Row(Modifier.width(gridWidth).fillMaxHeight()) {
                                var cursor = day
                                bySref[channel.ref].orEmpty()
                                    .filter { it.begin < nextDay(day) && it.end > day }
                                    .sortedBy { it.begin }
                                    .forEach { event ->
                                        val eventStart = maxOf(event.begin, day)
                                        val eventEnd = minOf(event.end, nextDay(day))
                                        if (eventStart > cursor) {
                                            Spacer(Modifier.width(((eventStart - cursor) / 60f * dpPerMin).dp))
                                        }
                                        if (eventEnd > eventStart) {
                                            val running = today && now in event.begin until event.end
                                            Column(
                                                Modifier.width(((eventEnd - eventStart) / 60f * dpPerMin).dp)
                                                    .fillMaxHeight()
                                                    .border(.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                                    .background(
                                                        if (running) MaterialTheme.colorScheme.primaryContainer
                                                        else MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                    .clickable { openEvent(event) }
                                                    .padding(3.dp)
                                            ) {
                                                Text(
                                                    event.title,
                                                    fontSize = titleSize,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = if (running) FontWeight.Bold else FontWeight.Normal
                                                )
                                                Text(
                                                    timeFmt.format(Date(event.begin * 1000)),
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            cursor = eventEnd
                                        }
                                    }
                            }
                            if (today) {
                                Box(
                                    Modifier.offset(x = nowOffset.dp).width(2.dp).fillMaxHeight()
                                        .background(Color.Red).zIndex(3f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
