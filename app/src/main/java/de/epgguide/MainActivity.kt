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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val HOURS = 24
private const val STALE_SECONDS = 6 * 3600L
private val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val dateFmt = SimpleDateFormat("EE dd.MM.", Locale.GERMANY)

private enum class ViewMode { EPG, NOW, PRIME }
private enum class UiSize { COMPACT, NORMAL, LARGE }

private fun dayStart(offset: Int = 0): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, offset)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000
private fun nowHalfHour() = System.currentTimeMillis() / 1000 / 1800 * 1800
private fun moveDay(timestamp: Long, amount: Int): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp * 1000
    add(Calendar.DAY_OF_YEAR, amount)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000
private fun nextDay(timestamp: Long) = moveDay(timestamp, 1)
private fun prime(day: Long) = day + 20 * 3600 + 15 * 60
private fun isMarker(ref: String) = ref.startsWith("1:64:")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colors) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable private fun Picon(receiver: Receiver, channel: Channel, size: UiSize) {
    val bmp by produceState<Bitmap?>(null, channel.ref, receiver) {
        value = withContext(Dispatchers.IO) { receiver.picon(channel) }
    }
    val w = when (size) { UiSize.COMPACT -> 48.dp; UiSize.NORMAL -> 60.dp; UiSize.LARGE -> 72.dp }
    val h = when (size) { UiSize.COMPACT -> 26.dp; UiSize.NORMAL -> 32.dp; UiSize.LARGE -> 40.dp }
    bmp?.let { Image(it.asImageBitmap(), channel.name, Modifier.size(w, h), contentScale = ContentScale.Fit) }
        ?: Spacer(Modifier.size(w, h))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }
    val cache = remember { EpgCache(ctx.filesDir) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var bouquets by remember { mutableStateOf<List<Bouquet>>(emptyList()) }
    var bouquet by remember { mutableStateOf(prefs.getString("bref", null)?.let { Bouquet(prefs.getString("bname", "") ?: "", it) }) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var selectedDay by remember { mutableLongStateOf(dayStart()) }
    var start by remember { mutableLongStateOf(nowHalfHour()) }
    var status by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Event?>(null) }
    var dayChannel by remember { mutableStateOf<Channel?>(null) }
    var showSettings by remember { mutableStateOf(host.isBlank()) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ViewMode.EPG) }
    var loadKey by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    var bouquetMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var uiSize by remember { mutableStateOf(runCatching { UiSize.valueOf(prefs.getString("uiSize", "NORMAL") ?: "NORMAL") }.getOrDefault(UiSize.NORMAL)) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    val loadedDays = remember { mutableStateMapOf<Long, Boolean>() }
    val receiver = remember(host, user, pass) { Receiver(host.trim(), user, pass, ctx.cacheDir) }
    val bySref = remember(events) { events.groupBy { it.sref } }

    fun saveFavorites(next: Set<String>) { favorites = next; prefs.edit().putStringSet("favorites", next).apply() }
    fun requestDay(day: Long) { selectedDay = day; start = day; loadKey++ }

    LaunchedEffect(loadKey, bouquet?.ref) {
        if (host.isBlank()) return@LaunchedEffect
        try {
            status = "Verbinde …"
            if (bouquets.isEmpty()) bouquets = withContext(Dispatchers.IO) { receiver.bouquets() }
            var b = bouquet
            if (b == null) { b = bouquets.firstOrNull() ?: error("Keine Bouquets gefunden"); bouquet = b }
            prefs.edit().putString("bref", b.ref).putString("bname", b.name).apply()
            val cached = withContext(Dispatchers.IO) { cache.load(host.trim(), b.ref) }
            if (channels.isEmpty() && cached != null) { channels = cached.channels; events = cached.events }
            if (channels.isEmpty() || force) channels = withContext(Dispatchers.IO) { receiver.channels(b.ref) }
            val day = selectedDay
            val stale = cached == null || System.currentTimeMillis()/1000 - cached.ts > STALE_SECONDS
            if (force || loadedDays[day] != true || stale && day == dayStart()) {
                status = "Lade ${dateFmt.format(Date(day * 1000))} …"
                val fresh = withContext(Dispatchers.IO) {
                    val list = receiver.epg(b.ref, day, nextDay(day))
                    if (day == dayStart()) list + receiver.epgNow(b.ref) else list
                }
                val merged = LinkedHashMap<String, Event>()
                events.forEach { merged["${it.sref}|${it.begin}"] = it }
                fresh.forEach { merged["${it.sref}|${it.begin}"] = it }
                events = merged.values.toList()
                loadedDays[day] = true
                cache.save(host.trim(), b.ref, System.currentTimeMillis()/1000, merged.values.maxOfOrNull { it.end } ?: nextDay(day), channels, events)
            }
            force = false; status = ""
        } catch (e: Exception) { status = "Fehler: ${e.message}"; force = false }
    }

    val rowHeight = when (uiSize) { UiSize.COMPACT -> 48.dp; UiSize.NORMAL -> 60.dp; UiSize.LARGE -> 74.dp }
    val logoWidth = when (uiSize) { UiSize.COMPACT -> 76.dp; UiSize.NORMAL -> 92.dp; UiSize.LARGE -> 110.dp }
    val dpPerMin = when (uiSize) { UiSize.COMPACT -> 1.4f; UiSize.NORMAL -> 2.0f; UiSize.LARGE -> 2.7f }
    val titleSize = when (uiSize) { UiSize.COMPACT -> 11.sp; UiSize.NORMAL -> 13.sp; UiSize.LARGE -> 16.sp }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(bouquet?.name ?: "EPG", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            TextButton({ showSearch = true }) { Text("🔍") }
            TextButton({ showSettings = true }) { Text("⚙") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            listOf(ViewMode.EPG to "EPG", ViewMode.NOW to "Jetzt", ViewMode.PRIME to "20:15").forEachIndexed { i, item ->
                SegmentedButton(mode == item.first, { mode = item.first }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(item.second) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ requestDay(moveDay(selectedDay, -1)) }) { Text("◀") }
            for (offset in 0..6) {
                val d = dayStart(offset)
                FilterChip(selectedDay == d, { requestDay(d) }, { Text(if (offset == 0) "Heute" else dateFmt.format(Date(d*1000))) }, Modifier.padding(horizontal = 2.dp))
            }
            TextButton({ requestDay(moveDay(selectedDay, 1)) }) { Text("▶") }
            TextButton({ selectedDay = dayStart(); start = nowHalfHour(); mode = ViewMode.EPG; loadKey++ }) { Text("Jetzt") }
            TextButton({ selectedDay = dayStart(); start = prime(dayStart()); mode = ViewMode.EPG; loadKey++ }) { Text("20:15") }
            TextButton({ force = true; loadedDays.remove(selectedDay); loadKey++ }) { Text("↻") }
        }
        if (status.isNotBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, Modifier.padding(horizontal = 10.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

        val visibleChannels = remember(channels, favorites) {
            val normal = channels.filterNot { isMarker(it.ref) }
            if (favorites.isEmpty()) normal else normal.sortedByDescending { it.ref in favorites }
        }
        when (mode) {
            ViewMode.NOW -> ProgrammeList(visibleChannels, bySref, System.currentTimeMillis()/1000, receiver, uiSize, favorites, ::saveFavorites, { dayChannel = it }, { detail = it })
            ViewMode.PRIME -> ProgrammeList(visibleChannels, bySref, prime(selectedDay), receiver, uiSize, favorites, ::saveFavorites, { dayChannel = it }, { detail = it })
            ViewMode.EPG -> EpgGrid(visibleChannels, bySref, start, selectedDay, receiver, uiSize, rowHeight, logoWidth, dpPerMin, titleSize, favorites, ::saveFavorites, { dayChannel = it }, { detail = it }, landscape)
        }
    }

    detail?.let { e -> AlertDialog({ detail=null }, { TextButton({detail=null}){Text("OK")} }, title={Text(e.title)}, text={Text("${timeFmt.format(Date(e.begin*1000))}–${timeFmt.format(Date(e.end*1000))}\n\n${e.desc}")}) }
    dayChannel?.let { ch ->
        val list = bySref[ch.ref].orEmpty().filter { it.begin < nextDay(selectedDay) && it.end > selectedDay }.sortedBy { it.begin }
        AlertDialog({dayChannel=null},{TextButton({dayChannel=null}){Text("Schließen")}}, title={Text("${ch.name} · ${dateFmt.format(Date(selectedDay*1000))}")}, text={
            if(list.isEmpty()) Text("Keine Programmdaten für diesen Tag.") else LazyColumn(Modifier.fillMaxWidth().heightIn(max=560.dp)) { items(list,key={"${it.sref}|${it.begin}"}) { e ->
                Column(Modifier.fillMaxWidth().clickable{dayChannel=null;detail=e}.padding(8.dp)) { Text("${timeFmt.format(Date(e.begin*1000))}–${timeFmt.format(Date(e.end*1000))}", color=MaterialTheme.colorScheme.primary, fontWeight=FontWeight.Bold); Text(e.title); if(e.desc.isNotBlank()) Text(e.desc,maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=12.sp) }; HorizontalDivider()
            }}
        })
    }
    if(showSearch) {
        val results = events.filter { query.length >= 2 && (it.title.contains(query,true)||it.desc.contains(query,true)) }.sortedBy{it.begin}.take(200)
        AlertDialog({showSearch=false},{TextButton({showSearch=false}){Text("Schließen")}}, title={Text("Sendung suchen")}, text={Column{OutlinedTextField(query,{query=it},label={Text("Titel oder Beschreibung")},singleLine=true); Spacer(Modifier.height(8.dp)); LazyColumn(Modifier.heightIn(max=480.dp)){items(results,key={"${it.sref}|${it.begin}"}){e->Column(Modifier.fillMaxWidth().clickable{showSearch=false;detail=e}.padding(8.dp)){Text(e.title,fontWeight=FontWeight.Bold);Text("${dateFmt.format(Date(e.begin*1000))} ${timeFmt.format(Date(e.begin*1000))}",fontSize=12.sp)};HorizontalDivider()}}}})
    }
    if(showSettings) AlertDialog({showSettings=false},{TextButton({
        prefs.edit().putString("host",host).putString("user",user).putString("pass",pass).putString("uiSize",uiSize.name).apply(); channels=emptyList();events=emptyList();loadedDays.clear();showSettings=false;force=true;loadKey++
    }){Text("Speichern & laden")}}, dismissButton={TextButton({showSettings=false}){Text("Schließen")}}, title={Text("Einstellungen")}, text={Column{
        OutlinedTextField(host,{host=it},label={Text("Receiver-IP")},singleLine=true); OutlinedTextField(user,{user=it},label={Text("Benutzer")},singleLine=true); OutlinedTextField(pass,{pass=it},label={Text("Passwort")},singleLine=true,visualTransformation=PasswordVisualTransformation()); Spacer(Modifier.height(8.dp))
        Box{OutlinedButton({bouquetMenu=true},Modifier.fillMaxWidth()){Text(bouquet?.name?:"Bouquet auswählen",maxLines=1,overflow=TextOverflow.Ellipsis)};DropdownMenu(bouquetMenu,{bouquetMenu=false}){bouquets.forEach{b->DropdownMenuItem({Text(b.name)},{bouquet=b;channels=emptyList();events=emptyList();loadedDays.clear();prefs.edit().putString("bref",b.ref).putString("bname",b.name).apply();bouquetMenu=false;force=true;loadKey++})}}}
        Box{OutlinedButton({sizeMenu=true},Modifier.fillMaxWidth()){Text("Darstellung: ${when(uiSize){UiSize.COMPACT->"Kompakt";UiSize.NORMAL->"Normal";UiSize.LARGE->"Groß"}}")};DropdownMenu(sizeMenu,{sizeMenu=false}){UiSize.values().forEach{s->DropdownMenuItem({Text(when(s){UiSize.COMPACT->"Kompakt";UiSize.NORMAL->"Normal";UiSize.LARGE->"Groß"})},{uiSize=s;sizeMenu=false})}}}
    }})
}

@Composable private fun ProgrammeList(channels: List<Channel>, bySref: Map<String,List<Event>>, at: Long, receiver: Receiver, size: UiSize, favorites:Set<String>, save:(Set<String>)->Unit, openDay:(Channel)->Unit, openEvent:(Event)->Unit) {
    LazyColumn(Modifier.fillMaxSize()) { items(channels,key={it.ref}) { ch ->
        val e=bySref[ch.ref].orEmpty().firstOrNull{at>=it.begin&&at<it.end}
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).border(.5.dp,MaterialTheme.colorScheme.outlineVariant).padding(6.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.width(92.dp).clickable{openDay(ch)},horizontalAlignment=Alignment.CenterHorizontally){Picon(receiver,ch,size);Text(ch.name,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
            TextButton({save(if(ch.ref in favorites) favorites-ch.ref else favorites+ch.ref)}){Text(if(ch.ref in favorites)"★" else "☆")}
            if(e==null) Text("Keine Programmdaten",color=MaterialTheme.colorScheme.onSurfaceVariant) else Column(Modifier.weight(1f).clickable{openEvent(e)}){Text(e.title,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${timeFmt.format(Date(e.begin*1000))}–${timeFmt.format(Date(e.end*1000))}",fontSize=11.sp);val p=((at-e.begin).toFloat()/(e.end-e.begin).coerceAtLeast(1)).coerceIn(0f,1f);LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top=5.dp))}
        }
    }}
}

@Composable private fun EpgGrid(channels:List<Channel>,bySref:Map<String,List<Event>>,start:Long,day:Long,receiver:Receiver,size:UiSize,rowHeight:androidx.compose.ui.unit.Dp,logoWidth:androidx.compose.ui.unit.Dp,dpPerMin:Float,titleSize:androidx.compose.ui.unit.TextUnit,favorites:Set<String>,save:(Set<String>)->Unit,openDay:(Channel)->Unit,openEvent:(Event)->Unit,landscape:Boolean){
    val scroll=rememberScrollState(); LaunchedEffect(start,day,dpPerMin){scroll.scrollTo(((start-day)/60f*dpPerMin).toInt().coerceAtLeast(0))}; val width=(HOURS*60*dpPerMin).dp
    Row(Modifier.padding(start=logoWidth)){Row(Modifier.horizontalScroll(scroll).width(width)){for(i in 0 until 48)Text(timeFmt.format(Date((day+i*1800)*1000)),Modifier.width((30*dpPerMin).dp),fontSize=11.sp)}}
    LazyColumn(Modifier.fillMaxSize()){items(channels,key={it.ref}){ch->Row(Modifier.height(rowHeight).border(.5.dp,MaterialTheme.colorScheme.outlineVariant)){
        Column(Modifier.width(logoWidth).clickable{openDay(ch)}.padding(2.dp),horizontalAlignment=Alignment.CenterHorizontally){Picon(receiver,ch,size);Row(verticalAlignment=Alignment.CenterVertically){Text(ch.name,Modifier.weight(1f),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(ch.ref in favorites)"★" else "☆",Modifier.clickable{save(if(ch.ref in favorites)favorites-ch.ref else favorites+ch.ref)})}}
        Row(Modifier.horizontalScroll(scroll).width(width)){var cursor=day;bySref[ch.ref].orEmpty().filter{it.begin<nextDay(day)&&it.end>day}.sortedBy{it.begin}.forEach{e->val s=maxOf(e.begin,day);val en=minOf(e.end,nextDay(day));if(s>cursor)Spacer(Modifier.width(((s-cursor)/60f*dpPerMin).dp));if(en>s){val now=System.currentTimeMillis()/1000;Column(Modifier.width(((en-s)/60f*dpPerMin).dp).fillMaxHeight().border(.5.dp,MaterialTheme.colorScheme.outlineVariant).background(if(now in e.begin until e.end)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).clickable{openEvent(e)}.padding(3.dp)){Text(e.title,fontSize=titleSize,maxLines=if(landscape)2 else 1,overflow=TextOverflow.Ellipsis);Text(timeFmt.format(Date(e.begin*1000)),fontSize=10.sp)};cursor=en}}}
    }}}
}
