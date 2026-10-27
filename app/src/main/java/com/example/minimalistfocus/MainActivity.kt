package com.example.minimalistfocus

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Paper = Color(0xFFF8F7F2)
private val Ink = Color(0xFF242424)
private val MutedInk = Color(0xFF85837D)
private val LightLine = Color(0xFFE8E5DC)

private const val PREFS = "minimalist_focus_v3"
private const val DATA_KEY = "journal_lines"
private const val TIMER_DURATION_KEY = "pomodoro_duration_seconds"
private const val TIMER_RUNNING_KEY = "pomodoro_running"
private const val TIMER_END_KEY = "pomodoro_end_at"
private const val TIMER_REMAINING_KEY = "pomodoro_remaining"

private enum class LineStatus(val symbol: String, val label: String) {
    TASK("•", "Task"), NEXT(">", "Next"), DONE("×", "Done"), PARTIAL("◐", "Partial")
}

private data class NoteLine(
    val id: Long,
    val time: String,
    val text: String = "",
    val status: LineStatus = LineStatus.TASK,
    val indent: Int = 0,
    val hasBullet: Boolean = true,
    val isBold: Boolean = false,
    val showTimestamp: Boolean = false
)

private data class InstalledApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MinimalistFocusScreen() } }
    }
}

@Composable
private fun MinimalistFocusScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun loadLines(): MutableList<NoteLine> {
        val result = mutableListOf<NoteLine>()
        val raw = prefs.getString(DATA_KEY, null)
        if (!raw.isNullOrBlank()) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    result.add(NoteLine(
                        id = obj.optLong("id", System.nanoTime() + i),
                        time = obj.optString("time", ""),
                        text = obj.optString("text", ""),
                        status = runCatching { LineStatus.valueOf(obj.optString("status", "TASK")) }.getOrDefault(LineStatus.TASK),
                        indent = obj.optInt("indent", 0).coerceIn(0, 8),
                        hasBullet = obj.optBoolean("hasBullet", true),
                        isBold = obj.optBoolean("isBold", false),
                        showTimestamp = obj.optBoolean("showTimestamp", false)
                    ))
                }
            } catch (_: Exception) { result.clear() }
        }
        if (result.isEmpty()) result.add(newNoteLine())
        return result
    }

    var lines by remember { mutableStateOf(loadLines()) }
    var editMode by remember { mutableStateOf(false) }
    var activeLineId by remember { mutableLongStateOf(lines.firstOrNull()?.id ?: 0L) }
    var statusLineId by remember { mutableStateOf<Long?>(null) }
    var showAppSearch by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableIntStateOf(0) } // 0 journal, 1 Pomodoro
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var timerDuration by remember { mutableIntStateOf(prefs.getInt(TIMER_DURATION_KEY, 25 * 60).coerceIn(60, 120 * 60)) }
    var timerRunning by remember { mutableStateOf(prefs.getBoolean(TIMER_RUNNING_KEY, false)) }
    var timerEndAt by remember { mutableLongStateOf(prefs.getLong(TIMER_END_KEY, 0L)) }
    var remainingSeconds by remember {
        mutableIntStateOf(
            if (prefs.getBoolean(TIMER_RUNNING_KEY, false) && prefs.getLong(TIMER_END_KEY, 0L) > 0L) {
                ((prefs.getLong(TIMER_END_KEY, 0L) - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            } else prefs.getInt(TIMER_REMAINING_KEY, prefs.getInt(TIMER_DURATION_KEY, 25 * 60)).coerceAtLeast(0)
        )
    }
    var pendingFocus by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val focusRequesters = remember { mutableStateMapOf<Long, FocusRequester>() }

    fun saveLines(updated: List<NoteLine>) {
        val array = JSONArray()
        updated.forEach { line -> array.put(JSONObject().apply {
            put("id", line.id); put("time", line.time); put("text", line.text)
            put("status", line.status.name); put("indent", line.indent)
            put("hasBullet", line.hasBullet); put("isBold", line.isBold)
            put("showTimestamp", line.showTimestamp)
        }) }
        prefs.edit().putString(DATA_KEY, array.toString()).apply()
    }
    fun saveTimer() {
        prefs.edit().putInt(TIMER_DURATION_KEY, timerDuration)
            .putBoolean(TIMER_RUNNING_KEY, timerRunning)
            .putLong(TIMER_END_KEY, timerEndAt)
            .putInt(TIMER_REMAINING_KEY, remainingSeconds).apply()
    }
    fun updateLine(id: Long, transform: (NoteLine) -> NoteLine) {
        lines = lines.map { if (it.id == id) transform(it) else it }.toMutableList()
        saveLines(lines)
    }
    fun focusLine(id: Long, cursor: Int) {
        activeLineId = id
        pendingFocus = id to cursor
        scope.launch {
            val index = lines.indexOfFirst { it.id == id }
            if (index >= 0) listState.animateScrollToItem(index)
        }
    }
    fun addLineAfter(id: Long, initialText: String = "") {
        val index = lines.indexOfFirst { it.id == id }
        val current = lines.getOrNull(index)
        val newLine = newNoteLine().copy(text = initialText, indent = current?.indent ?: 0, hasBullet = true,
            status = LineStatus.TASK, showTimestamp = current?.showTimestamp ?: false)
        val updated = lines.toMutableList()
        if (index >= 0) updated.add(index + 1, newLine) else updated.add(newLine)
        lines = updated
        saveLines(lines)
        focusLine(newLine.id, 0)
    }
    fun deleteOrMergeAtStart(id: Long, cursor: Int): Boolean {
        val index = lines.indexOfFirst { it.id == id }
        if (index <= 0 || cursor != 0) return false
        val current = lines[index]
        val previous = lines[index - 1]
        val updated = lines.toMutableList()
        val merged = previous.text + current.text
        updated[index - 1] = previous.copy(text = merged)
        updated.removeAt(index)
        lines = updated
        saveLines(lines)
        focusLine(previous.id, previous.text.length)
        return true
    }

    // Persist countdown based on an absolute end time; app restarts don't reset a running timer.
    LaunchedEffect(timerRunning, timerEndAt) {
        while (timerRunning) {
            val seconds = ((timerEndAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            remainingSeconds = seconds
            if (seconds <= 0) {
                timerRunning = false
                timerEndAt = 0L
                remainingSeconds = 0
                saveTimer()
                break
            }
            delay(250L)
        }
    }
    LaunchedEffect(Unit) {
        if (timerRunning) {
            if (remainingSeconds <= 0) {
                timerRunning = false; timerEndAt = 0L; remainingSeconds = 0; saveTimer()
            } else saveTimer()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Paper).imePadding()
            .pointerInput(currentScreen) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        if (dragTotal < -70f) currentScreen = 1
                        else if (dragTotal > 70f) currentScreen = 0
                        dragTotal = 0f
                    }
                )
            }
    ) {
        if (currentScreen == 0) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Today", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Medium)
                        Text(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()), color = MutedInk, fontSize = 12.sp)
                    }
                    Text("⌕", fontSize = 29.sp, color = Ink, modifier = Modifier.clickable { showAppSearch = true }.padding(horizontal = 12.dp, vertical = 8.dp))
                    Text(if (editMode) "◉" else "✎", fontSize = 24.sp, color = Ink,
                        modifier = Modifier.clickable { editMode = !editMode }.padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 8.dp))
                }
                Spacer(Modifier.height(10.dp)); HorizontalDivider(color = LightLine)
                if (editMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ToolButton("←", "Outdent") { updateLine(activeLineId) { it.copy(indent = (it.indent - 1).coerceAtLeast(0)) } }
                        ToolButton("→", "Indent") { updateLine(activeLineId) { it.copy(indent = (it.indent + 1).coerceAtMost(8)) } }
                        ToolButton("•", "Toggle task bullet") { updateLine(activeLineId) { it.copy(hasBullet = !it.hasBullet) } }
                        ToolButton("B", "Toggle bold") { updateLine(activeLineId) { it.copy(isBold = !it.isBold) } }
                        ToolButton("◷", "Toggle timestamp for this line") { updateLine(activeLineId) { it.copy(showTimestamp = !it.showTimestamp) } }
                        ToolButton("+", "Add line") { addLineAfter(activeLineId) }
                    }
                    HorizontalDivider(color = LightLine)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(top = 5.dp, bottom = 12.dp)) {
                    itemsIndexed(lines, key = { _, line -> line.id }) { _, line ->
                        val requester = remember(line.id) { FocusRequester() }
                        SideEffect { focusRequesters[line.id] = requester }
                        var fieldValue by remember(line.id) { mutableStateOf(TextFieldValue(line.text, selection = TextRange(line.text.length))) }
                        LaunchedEffect(line.text) {
                            if (line.text != fieldValue.text) fieldValue = TextFieldValue(line.text, selection = TextRange(line.text.length))
                        }
                        LaunchedEffect(pendingFocus) {
                            val target = pendingFocus
                            if (target?.first == line.id) {
                                requester.requestFocus()
                                fieldValue = fieldValue.copy(selection = TextRange(target.second.coerceIn(0, fieldValue.text.length)))
                                pendingFocus = null
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                            // Always reserve this column, even when the timestamp is hidden.
                            Box(Modifier.width(52.dp).padding(top = 4.dp)) {
                                if (line.showTimestamp) Text(line.time, color = MutedInk, fontSize = 11.sp, maxLines = 1)
                            }
                            Row(Modifier.weight(1f).padding(start = (line.indent * 18).dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.width(24.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) {
                                    if (line.hasBullet) Text(
                                        line.status.symbol,
                                        color = if (line.status == LineStatus.DONE) MutedInk else Ink,
                                        fontSize = 17.sp,
                                        modifier = Modifier.then(if (editMode) Modifier.clickable { statusLineId = line.id } else Modifier).padding(horizontal = 3.dp)
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    if (editMode) {
                                        BasicTextField(
                                            value = fieldValue,
                                            onValueChange = { newValue ->
                                                val oldText = fieldValue.text
                                                val newText = newValue.text
                                                if (newText.contains('\n')) {
                                                    val cursor = newValue.selection.start.coerceIn(0, newText.length)
                                                    val newlineAt = newText.indexOf('\n')
                                                    val before = newText.substring(0, newlineAt)
                                                    val after = newText.substring(newlineAt + 1)
                                                    updateLine(line.id) { it.copy(text = before) }
                                                    fieldValue = TextFieldValue(before, selection = TextRange(before.length))
                                                    addLineAfter(line.id, after)
                                                } else {
                                                    fieldValue = newValue
                                                    activeLineId = line.id
                                                    updateLine(line.id) { it.copy(text = newText) }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { if (it.isFocused) activeLineId = line.id }
                                                .onPreviewKeyEvent { event ->
                                                    if (event.key == Key.Backspace && event.type == KeyEventType.KeyDown && fieldValue.selection.collapsed && fieldValue.selection.start == 0) {
                                                        deleteOrMergeAtStart(line.id, 0)
                                                    } else false
                                                },
                                            textStyle = TextStyle(color = if (line.status == LineStatus.DONE) MutedInk else Ink, fontSize = 16.sp, lineHeight = 21.sp,
                                                fontWeight = if (line.isBold) FontWeight.Bold else FontWeight.Normal),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                                        )
                                    } else Text(line.text, color = if (line.status == LineStatus.DONE) MutedInk else Ink,
                                        fontSize = 16.sp, lineHeight = 21.sp, fontWeight = if (line.isBold) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = LightLine)
                Text("FOCUS ON WHAT MATTERS", color = MutedInk, fontSize = 10.sp, letterSpacing = 2.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp))
            }
        } else {
            PomodoroScreen(
                duration = timerDuration,
                remaining = remainingSeconds,
                running = timerRunning,
                onStartPause = {
                    if (timerRunning) {
                        remainingSeconds = ((timerEndAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                        timerRunning = false; timerEndAt = 0L
                    } else if (remainingSeconds > 0) {
                        timerEndAt = System.currentTimeMillis() + remainingSeconds * 1000L
                        timerRunning = true
                    }
                    saveTimer()
                },
                onReset = { timerRunning = false; timerEndAt = 0L; remainingSeconds = timerDuration; saveTimer() },
                onDurationChange = { seconds ->
                    timerDuration = seconds; timerRunning = false; timerEndAt = 0L; remainingSeconds = seconds; saveTimer()
                },
                onBack = { currentScreen = 0 }
            )
        }
    }

    val selectedStatusLine = lines.firstOrNull { it.id == statusLineId }
    if (editMode && selectedStatusLine != null) AlertDialog(
        onDismissRequest = { statusLineId = null }, containerColor = Paper,
        title = { Text("Change line status", color = Ink) },
        text = { Column { LineStatus.values().forEach { status -> Text("${status.symbol}   ${status.label}", color = Ink, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().clickable { updateLine(selectedStatusLine.id) { it.copy(status = status) }; statusLineId = null }.padding(vertical = 9.dp)) } } },
        confirmButton = { TextButton(onClick = { statusLineId = null }) { Text("Cancel", color = Ink) } }
    )
    if (showAppSearch) AppSearchDialog(onDismiss = { showAppSearch = false }, onLaunch = { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it) }
        showAppSearch = false
    })
}

@Composable
private fun PomodoroScreen(
    duration: Int,
    remaining: Int,
    running: Boolean,
    onStartPause: () -> Unit,
    onReset: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val minutes = remaining / 60
    val seconds = remaining % 60
    Column(Modifier.fillMaxSize().background(Paper).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 30.sp, color = Ink, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text("Pomodoro", fontSize = 25.sp, color = Ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("JOURNAL →", fontSize = 11.sp, color = MutedInk, modifier = Modifier.clickable(onClick = onBack).padding(6.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(if (running) "FOCUS SESSION" else "FOCUS TIMER", color = MutedInk, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(20.dp))
        Text(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds), color = Ink, fontSize = 62.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TimerButton(if (running) "Pause" else "Start", onClick = onStartPause, emphasized = true)
            TimerButton("Reset", onClick = onReset)
        }
        Spacer(Modifier.height(36.dp))
        Text("SESSION LENGTH", color = MutedInk, fontSize = 11.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 25, 45, 60).forEach { mins ->
                TimerButton("${mins}m", onClick = { onDurationChange(mins * 60) }, emphasized = duration == mins * 60)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("SWIPE RIGHT TO RETURN", color = MutedInk, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 18.dp))
    }
}

@Composable
private fun TimerButton(label: String, onClick: () -> Unit, emphasized: Boolean = false) {
    Text(label, color = Ink, fontSize = 15.sp, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.defaultMinSize(minWidth = 58.dp, minHeight = 44.dp)
            .background(if (emphasized) LightLine else Paper, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp))
}

private fun newNoteLine(): NoteLine = NoteLine(
    id = System.nanoTime(), time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
)

@Composable
private fun ToolButton(symbol: String, description: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.defaultMinSize(minWidth = 42.dp, minHeight = 42.dp)
            .background(LightLine.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(symbol, color = Ink, fontSize = 17.sp, maxLines = 1) }
}

@Composable
private fun AppSearchDialog(onDismiss: () -> Unit, onLaunch: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }
    val filteredApps = apps.filter { it.label.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Paper,
        title = { Text("Open app", color = Ink) },
        text = { Column {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search apps") })
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { _, app ->
                    Text(app.label, color = Ink, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onLaunch(app.packageName) }.padding(vertical = 10.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Ink) } }
    )
}
