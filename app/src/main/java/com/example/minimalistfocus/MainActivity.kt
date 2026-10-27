package com.example.minimalistfocus

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
private const val DARK_THEME_KEY = "dark_theme_enabled"
private const val TIMER_DURATION_KEY = "pomodoro_duration_seconds"
private const val TIMER_RUNNING_KEY = "pomodoro_running"
private const val TIMER_END_KEY = "pomodoro_end_at"
private const val TIMER_REMAINING_KEY = "pomodoro_remaining"

private enum class LineStatus(val symbol: String, val label: String) {
    NOTE("—", "Note"), TASK("•", "Task"), NEXT(">", "Next"), DONE("×", "Done"), PARTIAL("◐", "Partial")
}

private data class NoteLine(
    val id: Long,
    val time: String,
    val text: String = "",
    val status: LineStatus = LineStatus.NOTE,
    val indent: Int = 0,
    val hasBullet: Boolean = true,
    val isBold: Boolean = false,
    val showTimestamp: Boolean = false
)

private data class InstalledApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    private fun hideSystemNavigationBar() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemNavigationBar()
        setContent { MaterialTheme { MinimalistFocusScreen() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemNavigationBar()
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

    var isDarkTheme by remember { mutableStateOf(prefs.getBoolean(DARK_THEME_KEY, false)) }
    val paper = if (isDarkTheme) Color(0xFF202020) else Paper
    val ink = if (isDarkTheme) Color(0xFFECEBE6) else Ink
    val mutedInk = if (isDarkTheme) Color(0xFFAAA9A2) else MutedInk
    val lightLine = if (isDarkTheme) Color(0xFF44443F) else LightLine

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
            status = LineStatus.NOTE, showTimestamp = current?.showTimestamp ?: false)
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
        modifier = Modifier.fillMaxSize().background(paper).statusBarsPadding().imePadding().padding(top = 8.dp, bottom = 8.dp)
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
                        Text("Today", color = ink, fontSize = 25.sp, fontWeight = FontWeight.Medium)
                        Text(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()), color = mutedInk, fontSize = 12.sp)
                    }
                    Text(if (isDarkTheme) "☼" else "☾", fontSize = 24.sp, color = ink,
                        modifier = Modifier.clickable {
                            isDarkTheme = !isDarkTheme
                            prefs.edit().putBoolean(DARK_THEME_KEY, isDarkTheme).apply()
                        }.padding(horizontal = 9.dp, vertical = 10.dp))
                    Text("⌕", fontSize = 29.sp, color = ink, modifier = Modifier.clickable { showAppSearch = true }.padding(horizontal = 9.dp, vertical = 8.dp))
                    Text(if (editMode) "◉" else "✎", fontSize = 28.sp, color = ink,
                        modifier = Modifier.clickable { editMode = !editMode }.padding(start = 9.dp, end = 2.dp, top = 10.dp, bottom = 10.dp))
                }
                Spacer(Modifier.height(10.dp)); HorizontalDivider(color = lightLine)
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
                            // Keep the timestamp and text closer while reserving a consistent column.
                            Box(Modifier.width(44.dp).padding(top = 4.dp)) {
                                if (line.showTimestamp) Text(line.time, color = mutedInk, fontSize = 11.sp, maxLines = 1)
                            }
                            Row(Modifier.weight(1f).padding(start = (line.indent * 18).dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.width(20.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) {
                                    if (line.hasBullet) Text(
                                        line.status.symbol,
                                        color = if (line.status == LineStatus.DONE) mutedInk else ink,
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
                                            textStyle = TextStyle(color = if (line.status == LineStatus.DONE) mutedInk else ink, fontSize = 16.sp, lineHeight = 21.sp,
                                                fontWeight = if (line.isBold) FontWeight.Bold else FontWeight.Normal),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                                        )
                                    } else Text(line.text, color = if (line.status == LineStatus.DONE) mutedInk else ink,
                                        fontSize = 16.sp, lineHeight = 21.sp, fontWeight = if (line.isBold) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                if (editMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.border(1.dp, mutedInk.copy(alpha = 0.55f), RoundedCornerShape(28.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ToolButton("⇤", "Outdent", ink) { updateLine(activeLineId) { it.copy(indent = (it.indent - 1).coerceAtLeast(0)) } }
                            ToolButton("⇥", "Indent", ink) { updateLine(activeLineId) { it.copy(indent = (it.indent + 1).coerceAtMost(8)) } }
                            ToolButton("—", "Toggle line marker", ink) { updateLine(activeLineId) { it.copy(hasBullet = !it.hasBullet) } }
                            ToolButton("B", "Toggle bold", ink) { updateLine(activeLineId) { it.copy(isBold = !it.isBold) } }
                            ToolButton("◷", "Toggle timestamp for this line", ink) { updateLine(activeLineId) { it.copy(showTimestamp = !it.showTimestamp) } }
                            ToolButton("+", "Add line", ink) { addLineAfter(activeLineId) }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            PomodoroScreen(
                paper = paper, ink = ink, mutedInk = mutedInk, lightLine = lightLine,
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
        onDismissRequest = { statusLineId = null }, containerColor = paper,
        title = { Text("Change line status", color = ink) },
        text = { Column { LineStatus.values().forEach { status -> Text("${status.symbol}   ${status.label}", color = ink, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().clickable { updateLine(selectedStatusLine.id) { it.copy(status = status) }; statusLineId = null }.padding(vertical = 9.dp)) } } },
        confirmButton = { TextButton(onClick = { statusLineId = null }) { Text("Cancel", color = ink) } }
    )
    if (showAppSearch) AppSearchDialog(paper, ink, onDismiss = { showAppSearch = false }, onLaunch = { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it) }
        showAppSearch = false
    })
}

@Composable
private fun PomodoroScreen(
    paper: Color,
    ink: Color,
    mutedInk: Color,
    lightLine: Color,
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
    Column(Modifier.fillMaxSize().background(paper).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 30.sp, color = ink, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Text("Pomodoro", fontSize = 25.sp, color = ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("JOURNAL →", fontSize = 11.sp, color = mutedInk, modifier = Modifier.clickable(onClick = onBack).padding(6.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(if (running) "FOCUS SESSION" else "FOCUS TIMER", color = mutedInk, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(20.dp))
        Text(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds), color = ink, fontSize = 62.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TimerButton(if (running) "Pause" else "Start", ink, paper, lightLine, onClick = onStartPause, emphasized = true)
            TimerButton("Reset", ink, paper, lightLine, onClick = onReset)
        }
        Spacer(Modifier.height(36.dp))
        Text("SESSION LENGTH", color = mutedInk, fontSize = 11.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 25, 45, 60).forEach { mins ->
                TimerButton("${mins}m", ink, paper, lightLine, onClick = { onDurationChange(mins * 60) }, emphasized = duration == mins * 60)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("SWIPE RIGHT TO RETURN", color = mutedInk, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 18.dp))
    }
}

@Composable
private fun TimerButton(label: String, ink: Color, paper: Color, lightLine: Color, onClick: () -> Unit, emphasized: Boolean = false) {
    Text(label, color = ink, fontSize = 15.sp, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.defaultMinSize(minWidth = 58.dp, minHeight = 44.dp)
            .background(if (emphasized) lightLine else paper, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp))
}

private fun newNoteLine(): NoteLine = NoteLine(
    id = System.nanoTime(), time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
)

@Composable
private fun ToolButton(symbol: String, description: String, ink: Color, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(symbol, color = ink, fontSize = if (symbol == "B") 21.sp else 22.sp, maxLines = 1) }
}

@Composable
private fun AppSearchDialog(paper: Color, ink: Color, onDismiss: () -> Unit, onLaunch: (String) -> Unit) {
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
        onDismissRequest = onDismiss, containerColor = paper,
        title = { Text("Open app", color = ink) },
        text = { Column {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search apps") })
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { _, app ->
                    Text(app.label, color = ink, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onLaunch(app.packageName) }.padding(vertical = 10.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = ink) } }
    )
}