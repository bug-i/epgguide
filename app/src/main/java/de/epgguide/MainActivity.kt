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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
private const val HISTORY_DAYS = 2
private val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val dateFmt = SimpleDateFormat("EE dd.MM.", Locale.GERMANY)
private val dayHeaderFmt = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY)

private enum class UiSize { COMPACT, NORMAL, LARGE }

private fun dayStart(offset: Int = 0): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, offset)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private fun startOfDay(timestamp: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp * 1000
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
    var jumpKey by remember { mutableIntStateOf(0) }
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
        scrollTarget = if (day == dayStart()) System.currentTimeMillis() / 1000 else day
        jumpKey++
        loadKey++
    }

    fun jumpToNow() {
        selectedDay = dayStart()
        scrollTarget = System.currentTimeMillis() / 1000
        jumpKey++
        loadKey++
    }

    fun jumpToPrimeTime() {
        scrollTarget = primeTime(selectedDay)
        jumpKey++
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

            val daysToLoad = linkedSetOf(dayStart(-2), dayStart(-1), dayStart(), selectedDay)
            val merged = LinkedHashMap<String, Event>()
            events.forEach { merged["${it.sref}|${it.begin}"] = it }

            for (day in daysToLoad) {
                val staleToday = day == dayStart() &&
                    (cached == null || System.currentTimeMillis() / 1000 - cached.ts > STALE_SECONDS)
                if (force || loadedDays[day] != true || staleToday) {
                    status = "Aktualisiere ${dateFmt.format(Date(day * 1000))} …"
                    val fresh = withContext(Dispatchers.IO) {
                        val list = receiver.epg(selectedBouquet.ref, day, nextDay(day))
                        if (day == dayStart()) list + receiver.epgNow(selectedBouquet.ref) else list
                    }
                    fresh.forEach { merged["${it.sref}|${it.begin}"] = it }
                    loadedDays[day] = true
                    events = merged.values.toList()
                }
            }

            cache.save(
                host.trim(),
                selectedBouquet.ref,
                System.currentTimeMillis() / 1000,
                merged.values.maxOfOrNull { it.end } ?: nextDay(selectedDay),
                channels,
                merged.values.toList()
            )
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
                loadedDays.clear()
                loadKey++
            }) { Text("↻") }
            TextButton(onClick = { showSettings = true }) { Text("⚙") }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { requestDay(moveDay(selectedDay, -1)) }) { Text("◀") }
            for (offset in -HISTORY_DAYS..6) {
                val day = dayStart(offset)
                FilterChip(
                    selected = selectedDay == day,
                    onClick = { requestDay(day) },
                    label = {
                        Text(
                            when (offset) {
                                0 -> "Heute"
                                -1 -> "Gestern"
                                else -> dateFmt.format(Date(day * 1000))
                            }
                        )
                    },
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
            jumpKey = jumpKey,
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
                    "${dateFmt.format(Date(event.begin * 1000))}  " +
                        "${timeFmt.format(Date(event.begin * 1000))}–${timeFmt.format(Date(event.end * 1000))}" +
                        "  ·  $durationMinutes Min.\n\n${event.desc}"
                )
            }
        )
    }

    dayChannel?.let { channel ->
        ChannelOverview(
            channel = channel,
            events = bySref[channel.ref].orEmpty().sortedBy { it.begin },
            onClose = { dayChannel = null },
            onEvent = {
                dayChannel = null
                detail = it
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
                    OutlinedTextField(query, { query = it }, label = { Text("Titel oder Beschreibung") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(results, key = { "${it.sref}|${it.begin}" }) { event ->
                            val sender = channelByRef[event.sref]?.name ?: "Unbekannter Sender"
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    showSearch = false
                                    selectedDay = startOfDay(event.begin)
                                    scrollTarget = event.begin
                                    jumpKey++
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
                    prefs.edit().putString("host", host).putString("user", user).putString("pass", pass)
                        .putString("uiSize", uiSize.name).apply()
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
                    OutlinedTextField(pass, { pass = it }, label = { Text("Passwort") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedButton(onClick = { bouquetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(bouquet?.name ?: "Bouquet auswählen", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(bouquetMenu, { bouquetMenu = false }) {
                            bouquets.forEach { item ->
                                DropdownMenuItem(text = { Text(item.name) }, onClick = {
                                    bouquet = item
                                    channels = emptyList()
                                    events = emptyList()
                                    loadedDays.clear()
                                    prefs.edit().putString("bref", item.ref).putString("bname", item.name).apply()
                                    bouquetMenu = false
                                    force = true
                                    loadKey++
                                })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { sizeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Darstellung: ${when (uiSize) {
                                UiSize.COMPACT -> "Kompakt"
                                UiSize.NORMAL -> "Normal"
                                UiSize.LARGE -> "Groß"
                            }}")
                        }
                        DropdownMenu(sizeMenu, { sizeMenu = false }) {
                            UiSize.values().forEach { item ->
                                DropdownMenuItem(text = { Text(when (item) {
                                    UiSize.COMPACT -> "Kompakt"
                                    UiSize.NORMAL -> "Normal"
                                    UiSize.LARGE -> "Groß"
                                }) }, onClick = { uiSize = item; sizeMenu = false })
                            }
                        }
                    }
                }
            }
        )
    }
}

private sealed interface ChannelOverviewItem {
    data class Header(val day: Long) : ChannelOverviewItem
    data class Programme(val event: Event) : ChannelOverviewItem
}

@Composable
private fun ChannelOverview(
    channel: Channel,
    events: List<Event>,
    onClose: () -> Unit,
    onEvent: (Event) -> Unit
) {
    val now = System.currentTimeMillis() / 1000
    val oldest = dayStart(-HISTORY_DAYS)
    val filtered = events.filter { it.end > oldest }.sortedBy { it.begin }
    val rows = remember(filtered) {
        buildList<ChannelOverviewItem> {
            var lastDay: Long? = null
            filtered.forEach { event ->
                val eventDay = startOfDay(event.begin)
                if (eventDay != lastDay) {
                    add(ChannelOverviewItem.Header(eventDay))
                    lastDay = eventDay
                }
                add(ChannelOverviewItem.Programme(event))
            }
        }
    }
    val currentIndex = rows.indexOfFirst {
        it is ChannelOverviewItem.Programme && now in it.event.begin until it.event.end
    }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
        title = { Text(channel.name) },
        text = {
            if (rows.isEmpty()) {
                Text("Keine Programmdaten vorhanden.")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                ) {
                    itemsIndexed(rows, key = { index, item ->
                        when (item) {
                            is ChannelOverviewItem.Header -> "header-${item.day}"
                            is ChannelOverviewItem.Programme -> "event-$index-${item.event.begin}"
                        }
                    }) { _, item ->
                        when (item) {
                            is ChannelOverviewItem.Header -> Box(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    dayHeaderFmt.format(Date(item.day * 1000)).uppercase(Locale.GERMANY),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            is ChannelOverviewItem.Programme -> {
                                val event = item.event
                                val running = now in event.begin until event.end
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            if (running) MaterialTheme.colorScheme.primaryContainer
                                            else Color.Transparent
                                        )
                                        .clickable { onEvent(event) }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        "${timeFmt.format(Date(event.begin * 1000))}–${timeFmt.format(Date(event.end * 1000))}",
                                        color = if (running) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        event.title,
                                        fontWeight = if (running) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (event.desc.isNotBlank()) {
                                        Text(event.desc, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun EpgGrid(
    channels: List<Channel>,
    bySref: Map<String, List<Event>>,
    day: Long,
    scrollTarget: Long,
    jumpKey: Int,
    receiver: Receiver,
    size: UiSize,
    rowHeight: Dp,
    logoWidth: Dp,
    dpPerMin: Float,
    titleSize: TextUnit,
    openDay: (Channel) -> Unit,
    openEvent: (Event) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val gridWidthDp = HOURS * 60 * dpPerMin
    val viewportDp = (configuration.screenWidthDp.dp - logoWidth).coerceAtLeast(1.dp)
    val maxOffsetPx = with(density) { (gridWidthDp.dp - viewportDp).coerceAtLeast(0.dp).toPx() }
    var offsetPx by remember(day, dpPerMin) { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        offsetPx = (offsetPx - delta).coerceIn(0f, maxOffsetPx)
    }

    LaunchedEffect(scrollTarget, jumpKey, day, dpPerMin, maxOffsetPx) {
        val targetPositionPx = with(density) {
            (((scrollTarget - day) / 60f) * dpPerMin).dp.toPx()
        }
        val halfViewportPx = with(density) { viewportDp.toPx() / 2f }
        offsetPx = (targetPositionPx - halfViewportPx).coerceIn(0f, maxOffsetPx)
    }

    val today = day == dayStart()
    val now = System.currentTimeMillis() / 1000
    val nowPositionPx = with(density) { (((now - day) / 60f) * dpPerMin).dp.toPx() }
    val lineXpx = nowPositionPx - offsetPx
    val lineVisible = today && lineXpx in 0f..with(density) { viewportDp.toPx() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            Box(
                Modifier.width(logoWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(dateFmt.format(Date(day * 1000)), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .draggable(dragState, Orientation.Horizontal)
                    .clipToBounds()
            ) {
                Row(
                    Modifier.width(gridWidthDp.dp).fillMaxHeight()
                        .graphicsLayer { translationX = -offsetPx }
                ) {
                    for (index in 0 until 48) {
                        Text(
                            timeFmt.format(Date((day + index * 1800L) * 1000)),
                            Modifier.width((30 * dpPerMin).dp).padding(start = 2.dp),
                            fontSize = 11.sp
                        )
                    }
                }
                if (lineVisible) {
                    Box(
                        Modifier.offset { androidx.compose.ui.unit.IntOffset(lineXpx.toInt(), 0) }
                            .width(2.dp).fillMaxHeight().background(Color.Red)
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
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Picon(receiver, channel, size)
                            Text(channel.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .draggable(dragState, Orientation.Horizontal)
                                .clipToBounds()
                        ) {
                            Row(
                                Modifier.width(gridWidthDp.dp).fillMaxHeight()
                                    .graphicsLayer { translationX = -offsetPx }
                            ) {
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
                            if (lineVisible) {
                                Box(
                                    Modifier.offset { androidx.compose.ui.unit.IntOffset(lineXpx.toInt(), 0) }
                                        .width(2.dp).fillMaxHeight().background(Color.Red)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
