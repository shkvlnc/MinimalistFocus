
package com.example.minimalistfocus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF171717)
private val Paper = Color(0xFFFAF9F5)
private val Muted = Color(0xFF737373)
private val Rule = Color(0xFFD6D4CC)

private enum class EntryType { TASK, NOTE, EVENT }

private data class SubEntry(
    val id: Int,
    val text: String,
    val isTask: Boolean,
    val done: Boolean = false
)

private data class JournalEntry(
    val id: Int,
    val date: String,
    val time: String,
    val type: EntryType,
    val text: String,
    val done: Boolean = false,
    val children: List<SubEntry> = emptyList()
)

private const val PREFS_NAME = "minimalist_focus_v2"
private const val ENTRIES_KEY = "journal_entries"
private const val NEXT_ID_KEY = "next_entry_id"

private fun currentTime(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

private fun currentDate(): String = LocalDate.now().toString()

private fun encodeEntries(entries: List<JournalEntry>): String {
    val array = JSONArray()

    entries.forEach { entry ->
        val obj = JSONObject()
        obj.put("id", entry.id)
        obj.put("date", entry.date)
        obj.put("time", entry.time)
        obj.put("type", entry.type.name)
        obj.put("text", entry.text)
        obj.put("done", entry.done)

        val children = JSONArray()
        entry.children.forEach { child ->
            val childObj = JSONObject()
            childObj.put("id", child.id)
            childObj.put("text", child.text)
            childObj.put("isTask", child.isTask)
            childObj.put("done", child.done)
            children.put(childObj)
        }

        obj.put("children", children)
        array.put(obj)
    }

    return array.toString()
}

private fun decodeEntries(json: String): List<JournalEntry> {
    return try {
        val array = JSONArray(json)
        val result = mutableListOf<JournalEntry>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val childrenArray = obj.optJSONArray("children") ?: JSONArray()
            val children = mutableListOf<SubEntry>()

            for (j in 0 until childrenArray.length()) {
                val child = childrenArray.getJSONObject(j)
                children.add(
                    SubEntry(
                        id = child.optInt("id"),
                        text = child.optString("text"),
                        isTask = child.optBoolean("isTask"),
                        done = child.optBoolean("done")
                    )
                )
            }

            result.add(
                JournalEntry(
                    id = obj.optInt("id"),
                    date = obj.optString("date", currentDate()),
                    time = obj.optString("time"),
                    type = EntryType.valueOf(obj.optString("type")),
                    text = obj.optString("text"),
                    done = obj.optBoolean("done"),
                    children = children
                )
            )
        }

        result
    } catch (_: Exception) {
        emptyList()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Paper
                ) {
                    MinimalistFocusApp(applicationContext)
                }
            }
        }
    }
}

@Composable
private fun MinimalistFocusApp(context: Context) {
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var entries by remember {
        mutableStateOf(
            decodeEntries(prefs.getString(ENTRIES_KEY, "[]") ?: "[]")
        )
    }

    var nextId by remember {
        mutableIntStateOf(
            maxOf(
                prefs.getInt(NEXT_ID_KEY, 1),
                (entries.maxOfOrNull { it.id } ?: 0) + 1,
                (entries.flatMap { it.children }.maxOfOrNull { it.id } ?: 0) + 1
            )
        )
    }

    var currentPage by remember { mutableStateOf("Journal") }
    var expandedEntries by remember { mutableStateOf(setOf<Int>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf(EntryType.NOTE) }
    var parentForChild by remember { mutableStateOf<Int?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun saveEntries(updated: List<JournalEntry>) {
        entries = updated
        prefs.edit()
            .putString(ENTRIES_KEY, encodeEntries(updated))
            .putInt(NEXT_ID_KEY, nextId)
            .apply()
    }

    fun addEntry(text: String, type: EntryType) {
        val entry = JournalEntry(
            id = nextId++,
            date = currentDate(),
            time = currentTime(),
            type = type,
            text = text
        )
        saveEntries(listOf(entry) + entries)
    }

    val dateText = LocalDate.now().format(
        DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 18.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "MinimalistFocus",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Text(dateText, fontSize = 13.sp, color = Muted)
            }

            TextButton(onClick = { showSearch = true }) {
                Text("⌕", fontSize = 30.sp, color = Ink)
            }
        }

        Spacer(Modifier.height(14.dp))
        DividerLine()
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NotebookTab("JOURNAL", currentPage == "Journal") {
                currentPage = "Journal"
            }
            NotebookTab("TASKS", currentPage == "Tasks") {
                currentPage = "Tasks"
            }
        }

        Spacer(Modifier.height(12.dp))

        if (currentPage == "Journal") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TODAY'S ENTRIES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = Muted
                )
                TextButton(onClick = {
                    parentForChild = null
                    addType = EntryType.NOTE
                    showAddDialog = true
                }) {
                    Text("+ Add entry", color = Ink)
                }
            }

            val todaysEntries = entries.filter { it.date == currentDate() }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(todaysEntries, key = { it.id }) { entry ->
                    JournalEntryRow(
                        entry = entry,
                        expanded = entry.id in expandedEntries,
                        onToggleDone = {
                            saveEntries(entries.map {
                                if (it.id == entry.id) it.copy(done = !it.done)
                                else it
                            })
                        },
                        onExpand = {
                            expandedEntries =
                                if (entry.id in expandedEntries) {
                                    expandedEntries - entry.id
                                } else {
                                    expandedEntries + entry.id
                                }
                        },
                        onAddChild = {
                            parentForChild = entry.id
                            addType = EntryType.NOTE
                            showAddDialog = true
                        },
                        onToggleChild = { child ->
                            saveEntries(entries.map { parent ->
                                if (parent.id == entry.id) {
                                    parent.copy(
                                        children = parent.children.map {
                                            if (it.id == child.id) {
                                                it.copy(done = !it.done)
                                            } else it
                                        }
                                    )
                                } else parent
                            })
                        }
                    )
                    DividerLine()
                }

                item {
                    if (todaysEntries.isEmpty()) {
                        Text(
                            "A blank page. Start with one honest entry.",
                            fontSize = 14.sp,
                            color = Muted,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Every entry is a record of where your attention went.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Muted,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        } else {
            val tasks = entries.filter { it.type == EntryType.TASK }
            val pending = tasks.filterNot { it.done }
            val completed = tasks.filter { it.done }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "YOUR TASKS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = Muted
                )
                TextButton(onClick = {
                    addType = EntryType.TASK
                    parentForChild = null
                    showAddDialog = true
                }) {
                    Text("+ Add task", color = Ink)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Text(
                        "PENDING (${pending.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Muted,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }

                items(pending, key = { it.id }) { task ->
                    TaskRow(task) {
                        saveEntries(entries.map {
                            if (it.id == task.id) it.copy(done = true) else it
                        })
                    }
                }

                item {
                    Text(
                        "COMPLETED (${completed.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Muted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                items(completed, key = { it.id }) { task ->
                    TaskRow(task) {
                        saveEntries(entries.map {
                            if (it.id == task.id) it.copy(done = false) else it
                        })
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        Text(
            "FOCUS ON WHAT MATTERS",
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            color = Muted
        )
    }

    if (showAddDialog) {
        AddEntryDialog(
            title = if (parentForChild != null) "Add sub-entry" else when (addType) {
                EntryType.TASK -> "New task"
                EntryType.NOTE -> "New note"
                EntryType.EVENT -> "New event"
            },
            initialType = addType,
            showTypePicker = parentForChild == null,
            onDismiss = {
                showAddDialog = false
                parentForChild = null
            },
            onSave = { text, selectedType ->
                val parentId = parentForChild

                if (parentId == null) {
                    addEntry(text, selectedType)
                } else {
                    saveEntries(entries.map { parent ->
                        if (parent.id == parentId) {
                            parent.copy(
                                children = parent.children + SubEntry(
                                    id = nextId++,
                                    text = text,
                                    isTask = selectedType == EntryType.TASK
                                )
                            )
                        } else parent
                    })
                    expandedEntries = expandedEntries + parentId
                }

                showAddDialog = false
                parentForChild = null
            }
        )
    }

    if (showSearch) {
        val installedApps = remember(context) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            context.packageManager
                .queryIntentActivities(intent, 0)
                .map {
                    LauncherApp(
                        name = it.loadLabel(context.packageManager).toString(),
                        packageName = it.activityInfo.packageName
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
        }

        val matches = installedApps.filter {
            it.name.contains(searchQuery.trim(), ignoreCase = true)
        }

        AlertDialog(
            onDismissRequest = {
                showSearch = false
                searchQuery = ""
            },
            title = { Text("Find an app") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search installed apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(matches, key = { it.packageName }) { app ->
                            Text(
                                app.name,
                                color = Ink,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val launchIntent =
                                            context.packageManager
                                                .getLaunchIntentForPackage(app.packageName)

                                        if (launchIntent != null) {
                                            launchIntent.addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK
                                            )
                                            context.startActivity(launchIntent)
                                            showSearch = false
                                            searchQuery = ""
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSearch = false
                    searchQuery = ""
                }) {
                    Text("Close")
                }
            }
        )
    }
}

private data class LauncherApp(
    val name: String,
    val packageName: String
)

@Composable
private fun NotebookTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = if (selected) Ink else Muted
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .width(62.dp)
                .height(2.dp)
                .background(if (selected) Ink else Color.Transparent)
        )
    }
}

@Composable
private fun JournalEntryRow(
    entry: JournalEntry,
    expanded: Boolean,
    onToggleDone: () -> Unit,
    onExpand: () -> Unit,
    onAddChild: () -> Unit,
    onToggleChild: (SubEntry) -> Unit
) {
    val marker = when {
        entry.type == EntryType.TASK && entry.done -> "×"
        entry.type == EntryType.TASK -> "•"
        entry.type == EntryType.EVENT -> "○"
        else -> "–"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(marker, fontSize = 20.sp, color = Ink, modifier = Modifier.width(25.dp))

            Column(
                modifier = Modifier.weight(1f).clickable {
                    if (entry.type == EntryType.TASK) onToggleDone() else onExpand()
                }
            ) {
                Text(
                    entry.text,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = if (entry.done) Muted else Ink,
                    textDecoration = if (entry.done) TextDecoration.LineThrough
                    else TextDecoration.None
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${entry.time} · ${entry.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    fontSize = 11.sp,
                    color = Muted
                )
            }

            if (entry.children.isNotEmpty()) {
                TextButton(
                    onClick = onExpand,
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text(if (expanded) "▾" else "▸", color = Muted)
                }
            }
        }

        if (expanded && entry.children.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 25.dp, top = 8.dp)) {
                entry.children.forEach { child ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (child.isTask) onToggleChild(child)
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            when {
                                child.isTask && child.done -> "×"
                                child.isTask -> "•"
                                else -> "–"
                            },
                            fontSize = 16.sp,
                            color = Ink,
                            modifier = Modifier.width(22.dp)
                        )
                        Text(
                            child.text,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = if (child.done) Muted else Ink,
                            textDecoration = if (child.done) TextDecoration.LineThrough
                            else TextDecoration.None,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                TextButton(
                    onClick = onAddChild,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text("+ Add sub-task or note", fontSize = 12.sp, color = Muted)
                }
            }
        } else if (entry.type == EntryType.TASK && entry.children.isEmpty()) {
            TextButton(
                onClick = onAddChild,
                contentPadding = PaddingValues(
                    start = 25.dp, end = 0.dp, top = 0.dp, bottom = 0.dp
                )
            ) {
                Text("+ Add sub-entry", fontSize = 12.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun TaskRow(entry: JournalEntry, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = entry.done, onCheckedChange = { onToggle() })
        Text(
            entry.text,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            color = if (entry.done) Muted else Ink,
            textDecoration = if (entry.done) TextDecoration.LineThrough
            else TextDecoration.None
        )
    }
}

@Composable
private fun AddEntryDialog(
    title: String,
    initialType: EntryType,
    showTypePicker: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, EntryType) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(initialType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showTypePicker) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EntryType.values().forEach { option ->
                            TextButton(onClick = { type = option }) {
                                Text(
                                    when (option) {
                                        EntryType.TASK -> "• Task"
                                        EntryType.NOTE -> "– Note"
                                        EntryType.EVENT -> "○ Event"
                                    },
                                    color = if (type == option) Ink else Muted,
                                    fontWeight = if (type == option) FontWeight.Bold
                                    else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Write your entry...") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text.trim(), type) }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier.fillMaxWidth().height(1.dp).background(Rule)
    )
}