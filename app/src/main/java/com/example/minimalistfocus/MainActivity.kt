
package com.example.minimalistfocus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF202020)
private val Muted = Color(0xFF777777)
private val Rule = Color(0xFFE0E0E0)

private enum class EntryType { TASK, NOTE, EVENT }
private enum class TaskStatus { INBOX, NEXT, SCHEDULED, WAITING, DONE }
private enum class Page { JOURNAL, TASKS, PROJECTS, HISTORY }

private data class ChildEntry(
    val id: Long = System.nanoTime(),
    val text: String,
    val done: Boolean = false
)

private data class Entry(
    val id: Long = System.nanoTime(),
    val date: String = today(),
    val time: String = nowTime(),
    val type: EntryType = EntryType.TASK,
    val text: String,
    val details: String = "",
    val done: Boolean = false,
    val status: TaskStatus = TaskStatus.INBOX,
    val scheduledDate: String = "",
    val project: String = "",
    val children: List<ChildEntry> = emptyList()
)

private fun today(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

private fun nowTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun displayDate(date: String): String = try {
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)!!
    )
} catch (_: Exception) {
    date
}

private const val PREFS = "minimalist_focus_v2_scratch"
private const val DATA_KEY = "entries_json"

private fun encode(entries: List<Entry>): String {
    val array = JSONArray()
    entries.forEach { e ->
        val children = JSONArray()
        e.children.forEach { c ->
            children.put(
                JSONObject()
                    .put("id", c.id)
                    .put("text", c.text)
                    .put("done", c.done)
            )
        }

        array.put(
            JSONObject()
                .put("id", e.id)
                .put("date", e.date)
                .put("time", e.time)
                .put("type", e.type.name)
                .put("text", e.text)
                .put("details", e.details)
                .put("done", e.done)
                .put("status", e.status.name)
                .put("scheduledDate", e.scheduledDate)
                .put("project", e.project)
                .put("children", children)
        )
    }
    return array.toString()
}

private fun decode(json: String?): List<Entry> {
    if (json.isNullOrBlank()) return emptyList()

    return try {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val childrenJson = o.optJSONArray("children") ?: JSONArray()
            val children = List(childrenJson.length()) { j ->
                val c = childrenJson.getJSONObject(j)
                ChildEntry(
                    id = c.optLong("id", System.nanoTime()),
                    text = c.optString("text"),
                    done = c.optBoolean("done")
                )
            }

            Entry(
                id = o.optLong("id", System.nanoTime()),
                date = o.optString("date", today()),
                time = o.optString("time", nowTime()),
                type = runCatching {
                    EntryType.valueOf(o.optString("type", "TASK"))
                }.getOrDefault(EntryType.TASK),
                text = o.optString("text"),
                details = o.optString("details"),
                done = o.optBoolean("done"),
                status = runCatching {
                    TaskStatus.valueOf(o.optString("status", "INBOX"))
                }.getOrDefault(TaskStatus.INBOX),
                scheduledDate = o.optString("scheduledDate"),
                project = o.optString("project"),
                children = children
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = Paper,
                    surface = Paper,
                    primary = Ink,
                    onPrimary = Paper,
                    onSurface = Ink,
                    onBackground = Ink
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Paper
                ) {
                    MinimalistFocus()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MinimalistFocus() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var entries by remember {
        mutableStateOf(decode(prefs.getString(DATA_KEY, null)))
    }
    var page by remember { mutableStateOf(Page.JOURNAL) }
    var taskFilter by remember { mutableStateOf(TaskStatus.INBOX) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var parentForChild by remember { mutableStateOf<Entry?>(null) }
    var actionEntry by remember { mutableStateOf<Entry?>(null) }
    var deleteEntry by remember { mutableStateOf<Entry?>(null) }
    var showMove by remember { mutableStateOf<Entry?>(null) }
    var showApps by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var completedExpanded by remember { mutableStateOf(false) }
    var pomodoroEntry by remember { mutableStateOf<Entry?>(null) }
    var secondsLeft by remember { mutableIntStateOf(25 * 60) }
    var timerRunning by remember { mutableStateOf(false) }

    fun save(updated: List<Entry>) {
        entries = updated
        prefs.edit().putString(DATA_KEY, encode(updated)).apply()
    }

    fun upsert(entry: Entry) {
        save(
            if (entries.any { it.id == entry.id }) {
                entries.map { if (it.id == entry.id) entry else it }
            } else {
                entries + entry
            }
        )
    }

    fun toggleDone(entry: Entry) {
        upsert(
            entry.copy(
                done = !entry.done,
                status = if (!entry.done) TaskStatus.DONE
                else TaskStatus.NEXT
            )
        )
    }

    LaunchedEffect(timerRunning, secondsLeft) {
        if (timerRunning && secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        } else if (secondsLeft == 0) {
            timerRunning = false
        }
    }

    val todayEntries = entries
        .filter { it.date == today() }
        .sortedBy { it.time }

    val taskEntries = entries.filter { it.type == EntryType.TASK }
    val visibleTasks = taskEntries.filter { it.status == taskFilter }
    val projects = taskEntries.map { it.project.trim() }
        .filter { it.isNotBlank() }.distinct().sorted()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "MinimalistFocus",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            TextButton(onClick = { showApps = true }) {
                Text("⌕", fontSize = 25.sp, color = Ink)
            }
        }

        Text(
            when (page) {
                Page.JOURNAL -> "Journal"
                Page.TASKS -> "Tasks"
                Page.PROJECTS -> "Projects"
                Page.HISTORY -> "History"
            },
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            when (page) {
                Page.JOURNAL -> displayDate(today())
                Page.TASKS -> "Organize what needs doing"
                Page.PROJECTS -> "${projects.size} projects"
                Page.HISTORY -> "Past entries and completed tasks"
            },
            fontSize = 12.sp,
            color = Muted
        )

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Rule)
        Spacer(Modifier.height(8.dp))

        when (page) {
            Page.JOURNAL -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TODAY", fontSize = 11.sp, letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold, color = Muted)
                    TextButton(onClick = {
                        editing = null
                        showEditor = true
                    }) {
                        Text("+ Add entry", color = Ink)
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(todayEntries, key = { it.id }) { entry ->
                        JournalRow(
                            entry = entry,
                            onClick = {
                                if (entry.type == EntryType.TASK) {
                                    toggleDone(entry)
                                }
                            },
                            onLongClick = { actionEntry = entry },
                            onChildToggle = { child ->
                                upsert(
                                    entry.copy(
                                        children = entry.children.map {
                                            if (it.id == child.id)
                                                it.copy(done = !it.done)
                                            else it
                                        }
                                    )
                                )
                            },
                            onPomodoro = {
                                pomodoroEntry = entry
                                secondsLeft = 25 * 60
                                timerRunning = false
                            }
                        )
                    }
                }
            }

            Page.TASKS -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        TaskStatus.INBOX, TaskStatus.NEXT,
                        TaskStatus.SCHEDULED, TaskStatus.WAITING,
                        TaskStatus.DONE
                    ).forEach { status ->
                        val label = when (status) {
                            TaskStatus.INBOX -> "Inbox"
                            TaskStatus.NEXT -> "Next"
                            TaskStatus.SCHEDULED -> "Later"
                            TaskStatus.WAITING -> "Waiting"
                            TaskStatus.DONE -> "Done"
                        }
                        FilterChip(
                            selected = taskFilter == status,
                            onClick = { taskFilter = status },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(visibleTasks, key = { it.id }) { entry ->
                        JournalRow(
                            entry = entry,
                            onClick = { toggleDone(entry) },
                            onLongClick = { actionEntry = entry },
                            onChildToggle = { child ->
                                upsert(entry.copy(children = entry.children.map {
                                    if (it.id == child.id) it.copy(done = !it.done)
                                    else it
                                }))
                            },
                            onPomodoro = {
                                pomodoroEntry = entry
                                secondsLeft = 25 * 60
                                timerRunning = false
                            }
                        )
                    }
                }
            }

            Page.PROJECTS -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(projects) { project ->
                        val projectTasks = taskEntries.filter { it.project == project }
                        val completed = projectTasks.count { it.status == TaskStatus.DONE }

                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(project, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$completed of ${projectTasks.size} tasks completed",
                                fontSize = 12.sp,
                                color = Muted
                            )
                            LinearProgressIndicator(
                                progress = {
                                    if (projectTasks.isEmpty()) 0f
                                    else completed.toFloat() / projectTasks.size
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                color = Ink,
                                trackColor = Rule
                            )
                            projectTasks.filter { it.status != TaskStatus.DONE }
                                .forEach { entry ->
                                    JournalRow(
                                        entry = entry,
                                        onClick = { toggleDone(entry) },
                                        onLongClick = { actionEntry = entry },
                                        onChildToggle = { child ->
                                            upsert(entry.copy(children = entry.children.map {
                                                if (it.id == child.id) it.copy(done = !it.done)
                                                else it
                                            }))
                                        },
                                        onPomodoro = {
                                            pomodoroEntry = entry
                                            secondsLeft = 25 * 60
                                        }
                                    )
                                }
                            HorizontalDivider(color = Rule)
                        }
                    }
                }
                Text(
                    "Projects are created by assigning a project name when editing a task.",
                    fontSize = 11.sp,
                    color = Muted
                )
            }

            Page.HISTORY -> {
                val history = entries.filter {
                    it.date != today() || it.status == TaskStatus.DONE
                }.sortedByDescending { it.date + it.time }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(history, key = { it.id }) { entry ->
                        JournalRow(
                            entry = entry,
                            onClick = { toggleDone(entry) },
                            onLongClick = { actionEntry = entry },
                            onChildToggle = { child ->
                                upsert(entry.copy(children = entry.children.map {
                                    if (it.id == child.id) it.copy(done = !it.done)
                                    else it
                                }))
                            },
                            onPomodoro = {
                                pomodoroEntry = entry
                                secondsLeft = 25 * 60
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Rule)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(
                Page.JOURNAL to "Journal",
                Page.TASKS to "Tasks",
                Page.PROJECTS to "Projects",
                Page.HISTORY to "History"
            ).forEach { (destination, label) ->
                TextButton(onClick = { page = destination }) {
                    Text(
                        label,
                        color = if (page == destination) Ink else Muted,
                        fontWeight = if (page == destination) FontWeight.Bold
                        else FontWeight.Normal,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    if (showEditor) {
        EntryEditor(
            initial = editing,
            projects = projects,
            onDismiss = { showEditor = false; editing = null },
            onSave = { entry ->
                upsert(entry)
                showEditor = false
                editing = null
            }
        )
    }

    if (parentForChild != null) {
        ChildEditor(
            onDismiss = { parentForChild = null },
            onSave = { text ->
                val parent = parentForChild
                if (parent != null) {
                    upsert(parent.copy(children = parent.children + ChildEntry(text = text)))
                }
                parentForChild = null
            }
        )
    }

    if (actionEntry != null) {
        val entry = actionEntry!!
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text("Entry actions") },
            text = {
                Column {
                    Text(entry.text, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        editing = entry
                        actionEntry = null
                        showEditor = true
                    }) { Text("Edit") }
                    TextButton(onClick = {
                        parentForChild = entry
                        actionEntry = null
                    }) { Text("Add sub-entry") }
                    if (entry.type == EntryType.TASK) {
                        TextButton(onClick = {
                            showMove = entry
                            actionEntry = null
                        }) { Text("Move / organize") }
                    }
                    TextButton(onClick = {
                        deleteEntry = entry
                        actionEntry = null
                    }) { Text("Delete") }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionEntry = null }) { Text("Cancel") }
            }
        )
    }

    if (showMove != null) {
        val entry = showMove!!
        AlertDialog(
            onDismissRequest = { showMove = null },
            title = { Text("Move task") },
            text = {
                Column {
                    Text(entry.text, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    TaskStatus.values().forEach { status ->
                        TextButton(
                            onClick = {
                                upsert(entry.copy(
                                    status = status,
                                    done = status == TaskStatus.DONE
                                ))
                                showMove = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when (status) {
                                    TaskStatus.INBOX -> "Move to Inbox"
                                    TaskStatus.NEXT -> "Move to Next"
                                    TaskStatus.SCHEDULED -> "Move to Scheduled"
                                    TaskStatus.WAITING -> "Move to Waiting"
                                    TaskStatus.DONE -> "Move to Done"
                                },
                                color = Ink
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMove = null }) { Text("Cancel") }
            }
        )
    }

    if (deleteEntry != null) {
        val entry = deleteEntry!!
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("Delete entry?") },
            text = {
                Text("“${entry.text}” and its sub-entries will be permanently deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    save(entries.filterNot { it.id == entry.id })
                    deleteEntry = null
                }) { Text("Delete", color = Ink) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text("Cancel") }
            }
        )
    }

    if (pomodoroEntry != null) {
        AlertDialog(
            onDismissRequest = {
                timerRunning = false
                pomodoroEntry = null
            },
            title = { Text("Focus timer") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(pomodoroEntry!!.text, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "%02d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Light
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "25-minute focus session",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    timerRunning = !timerRunning
                }) {
                    Text(if (timerRunning) "Pause" else "Start", color = Ink)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    timerRunning = false
                    secondsLeft = 25 * 60
                }) { Text("Reset") }
            }
        )
    }

    if (showApps) {
        AppSearchDialog(
            query = searchText,
            onQueryChange = { searchText = it },
            onDismiss = { showApps = false; searchText = "" }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalRow(
    entry: Entry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onChildToggle: (ChildEntry) -> Unit,
    onPomodoro: () -> Unit
) {
    val symbol = when (entry.type) {
        EntryType.TASK -> if (entry.done) "×" else "•"
        EntryType.NOTE -> "–"
        EntryType.EVENT -> "○"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                symbol,
                modifier = Modifier.width(24.dp),
                fontSize = 22.sp,
                color = Ink
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.text,
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        color = if (entry.done) Muted else Ink,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        entry.time,
                        fontSize = 11.sp,
                        color = Muted
                    )
                }

                if (entry.details.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(entry.details, fontSize = 12.sp, color = Muted)
                }

                if (entry.scheduledDate.isNotBlank()) {
                    Text(
                        "Scheduled: ${displayDate(entry.scheduledDate)}",
                        fontSize = 11.sp,
                        color = Muted
                    )
                }

                if (entry.project.isNotBlank()) {
                    Text("Project: ${entry.project}", fontSize = 11.sp, color = Muted)
                }

                entry.children.forEach { child ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 7.dp)
                            .combinedClickable(
                                onClick = { onChildToggle(child) },
                                onLongClick = onLongClick
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (child.done) "×" else "◦",
                            modifier = Modifier.width(20.dp),
                            color = Muted,
                            fontSize = 16.sp
                        )
                        Text(
                            child.text,
                            fontSize = 13.sp,
                            color = if (child.done) Muted else Ink
                        )
                    }
                }

                if (entry.type == EntryType.TASK) {
                    TextButton(
                        onClick = onPomodoro,
                        contentPadding = PaddingValues(
                            horizontal = 0.dp,
                            vertical = 0.dp
                        )
                    ) {
                        Text("▶ 25", fontSize = 10.sp, color = Muted)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Rule, thickness = 0.5.dp)
}

@Composable
private fun EntryEditor(
    initial: Entry?,
    projects: List<String>,
    onDismiss: () -> Unit,
    onSave: (Entry) -> Unit
) {
    var text by remember(initial?.id) { mutableStateOf(initial?.text ?: "") }
    var details by remember(initial?.id) { mutableStateOf(initial?.details ?: "") }
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: EntryType.TASK) }
    var status by remember(initial?.id) { mutableStateOf(initial?.status ?: TaskStatus.INBOX) }
    var date by remember(initial?.id) { mutableStateOf(initial?.scheduledDate ?: "") }
    var project by remember(initial?.id) { mutableStateOf(initial?.project ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New entry" else "Edit entry") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Entry") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Notes / details") }
                )
                Spacer(Modifier.height(8.dp))
                Text("Type", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EntryType.values().forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = {
                                Text(
                                    option.name.lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    fontSize = 10.sp
                                )
                            }
                        )
                    }
                }

                if (type == EntryType.TASK) {
                    Text("Task list", fontWeight = FontWeight.Medium)
                    Column {
                        TaskStatus.values().forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = status == option,
                                    onClick = { status = option }
                                )
                                Text(
                                    option.name.lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Scheduled date (YYYY-MM-DD)") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = project,
                        onValueChange = { project = it },
                        label = { Text("Project (optional)") },
                        singleLine = true
                    )
                    if (projects.isNotEmpty()) {
                        Text(
                            "Existing projects: ${projects.joinToString()}",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val base = initial ?: Entry(text = text.trim())
                    onSave(
                        base.copy(
                            text = text.trim(),
                            details = details.trim(),
                            type = type,
                            status = if (type == EntryType.TASK) status else base.status,
                            done = if (type == EntryType.TASK)
                                status == TaskStatus.DONE else base.done,
                            scheduledDate = date.trim(),
                            project = project.trim()
                        )
                    )
                }
            ) { Text("Save", color = Ink) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ChildEditor(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add sub-entry") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Subtask or note") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text.trim()) }
            ) { Text("Add", color = Ink) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AppSearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0)
            .map {
                it.loadLabel(context.packageManager).toString() to
                        it.activityInfo.packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.getDefault()) }
    }
    val matching = apps.filter { it.first.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search installed apps") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    matching.take(40).forEach { (name, packageName) ->
                        TextButton(
                            onClick = {
                                val launch = context.packageManager
                                    .getLaunchIntentForPackage(packageName)
                                if (launch != null) {
                                    context.startActivity(launch)
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                name,
                                modifier = Modifier.fillMaxWidth(),
                                color = Ink
                            )
                        }
                    }
                    if (matching.isEmpty()) {
                        Text("No matching apps", color = Muted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
