
package com.example.minimalistfocus

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.content.Intent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private data class LauncherApp(
    val name: String,
    val packageName: String
)

private val Context.dataStore by preferencesDataStore(
    name = "minimalist_focus"
)

private val TASKS_KEY = stringPreferencesKey("tasks_json")
private val LOGS_KEY = stringPreferencesKey("logs_json")

private val Ink = Color(0xFF171717)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF737373)
private val Rule = Color(0xFFD6D6D6)

private data class TaskItem(
    val title: String,
    val done: Boolean = false
)

private data class JournalItem(
    val time: String,
    val text: String
)

private val sampleTasks = listOf(
    TaskItem("Review Java fundamentals"),
    TaskItem("Work on personal project"),
    TaskItem("Plan today's priorities", true)
)

private val sampleLogs = listOf(
    JournalItem(
        "09:15",
        "Need to focus on learning and make steady progress today."
    ),
    JournalItem(
        "08:30",
        "Ideas for the minimalist launcher and E-Ink reader."
    )
)

private fun encodeTasks(tasks: List<TaskItem>): String {
    val array = JSONArray()
    tasks.forEach { task ->
        array.put(
            JSONObject()
                .put("title", task.title)
                .put("done", task.done)
        )
    }
    return array.toString()
}

private fun decodeTasks(json: String?): List<TaskItem> {
    if (json == null) return sampleTasks

    return try {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            TaskItem(
                title = item.getString("title"),
                done = item.optBoolean("done", false)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun encodeLogs(logs: List<JournalItem>): String {
    val array = JSONArray()
    logs.forEach { log ->
        array.put(
            JSONObject()
                .put("time", log.time)
                .put("text", log.text)
        )
    }
    return array.toString()
}

private fun decodeLogs(json: String?): List<JournalItem> {
    if (json == null) return sampleLogs

    return try {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            JournalItem(
                time = item.getString("time"),
                text = item.getString("text")
            )
        }
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
                    FocusHome(applicationContext)
                }
            }
        }
    }
}

@Composable
private fun FocusHome(context: Context) {
    val scope = rememberCoroutineScope()

    val tasks by context.dataStore.data
        .collectAsState(initial = null)
        .let { preferences ->
            remember(preferences.value) {
                mutableStateOf(
                    preferences.value?.let {
                        decodeTasks(it[TASKS_KEY])
                    } ?: sampleTasks
                )
            }
        }

    val logs by context.dataStore.data
        .collectAsState(initial = null)
        .let { preferences ->
            remember(preferences.value) {
                mutableStateOf(
                    preferences.value?.let {
                        decodeLogs(it[LOGS_KEY])
                    } ?: sampleLogs
                )
            }
        }

    var showTaskDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var completedExpanded by remember { mutableStateOf(true) }
    var logToDelete by remember { mutableStateOf<JournalItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Today",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Text(
                    text = LocalDate.now().format(
                        DateTimeFormatter.ofPattern(
                            "EEEE, MMMM d",
                            Locale.getDefault()
                        )
                    ),
                    fontSize = 14.sp,
                    color = Muted
                )
            }


            TextButton(onClick = { showSearchDialog = true }) {
                Text("⌕", fontSize = 32.sp, color = Ink)
            }
        }

        Spacer(Modifier.height(16.dp))
        DividerLine()
        Spacer(Modifier.height(20.dp))

        Text(
            text = "TO-DO",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = Ink
        )

        Spacer(Modifier.height(8.dp))


        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val pendingTasks = tasks.withIndex()
                .filter { !it.value.done }

            val completedTasks = tasks.withIndex()
                .filter { it.value.done }

            Text(
                text = "PENDING (${pendingTasks.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Muted
            )

            Spacer(Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (completedExpanded) 1f else 1f)
                    .verticalScroll(rememberScrollState())
            ) {

                pendingTasks.forEach { indexedTask ->
                    val index = indexedTask.index
                    val task = indexedTask.value

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = task.done,
                            onCheckedChange = { checked ->
                                val updated = tasks.toMutableList()
                                updated[index] = task.copy(done = checked)

                                scope.launch {
                                    context.dataStore.edit { preferences ->
                                        preferences[TASKS_KEY] = encodeTasks(updated)
                                    }
                                }
                            }
                        )

                        Text(
                            text = task.title,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = Ink
                        )

                        TextButton(
                            onClick = {
                                scope.launch {
                                    context.dataStore.edit { preferences ->
                                        val current = decodeTasks(preferences[TASKS_KEY])
                                        val updated = current.filterNot {
                                            it.title == task.title && it.done == task.done
                                        }
                                        preferences[TASKS_KEY] = encodeTasks(updated)
                                    }
                                }
                            }
                        ) {
                            Text("×", color = Muted, fontSize = 22.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = {
                    completedExpanded = !completedExpanded
                },
                contentPadding = PaddingValues(
                    horizontal = 0.dp,
                    vertical = 0.dp
                )
            ) {
                Text(
                    text = "${if (completedExpanded) "▾" else "▸"} COMPLETED (${completedTasks.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = Muted
                )
            }

            Spacer(Modifier.height(4.dp))

            if (completedExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    completedTasks.forEach { indexedTask ->
                        val index = indexedTask.index
                        val task = indexedTask.value

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = task.done,
                                onCheckedChange = { checked ->
                                    val updated = tasks.toMutableList()
                                    updated[index] = task.copy(done = checked)

                                    scope.launch {
                                        context.dataStore.edit { preferences ->
                                            preferences[TASKS_KEY] = encodeTasks(updated)
                                        }
                                    }
                                }
                            )

                            Text(
                                text = task.title,
                                modifier = Modifier.weight(1f),
                                fontSize = 15.sp,
                                color = Muted,
                                textDecoration = TextDecoration.LineThrough
                            )

                            TextButton(
                                onClick = {
                                    scope.launch {
                                        context.dataStore.edit { preferences ->
                                            val current = decodeTasks(preferences[TASKS_KEY])
                                            val updated = current.filterNot {
                                                it.title == task.title && it.done == task.done
                                            }
                                            preferences[TASKS_KEY] = encodeTasks(updated)
                                        }
                                    }
                                }
                            ) {
                                Text("×", color = Muted, fontSize = 22.sp)
                            }
                        }
                    }
                }
                }

        TextButton(onClick = { showTaskDialog = true }) {
            Text("+ Add task", color = Muted, fontSize = 14.sp)
        }
        }
        Spacer(Modifier.height(12.dp))
        DividerLine()
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "JOURNAL LOGS",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Ink
            )

            TextButton(onClick = { showLogDialog = true }) {
                Text("+ Quick log", color = Muted, fontSize = 13.sp)
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(logs) { _, log ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = log.time,
                            fontSize = 12.sp,
                            color = Muted
                        )
                        Text(
                            text = log.text,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            color = Ink
                        )
                    }

                    TextButton(
                        onClick = { logToDelete = log },
                        contentPadding = PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp
                        )
                    ) {
                        Text("×", fontSize = 20.sp, color = Muted)
                    }
                }
            }
        }


        Spacer(Modifier.height(12.dp))
        DividerLine()
        Spacer(Modifier.height(10.dp))

        Text(
            text = "FOCUS ON WHAT MATTERS",
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            color = Muted
        )
    }

    if (showTaskDialog) {
        AddTextDialog(
            title = "New task",
            hint = "What do you need to do?",
            buttonLabel = "Add task",
            onDismiss = { showTaskDialog = false },
            onSave = { value ->
                scope.launch {
                    context.dataStore.edit { preferences ->
                        val current = decodeTasks(preferences[TASKS_KEY])
                        preferences[TASKS_KEY] = encodeTasks(
                            current + TaskItem(value)
                        )
                    }
                }
                showTaskDialog = false
            }
        )
    }

    if (showLogDialog) {
        AddTextDialog(
            title = "Quick journal log",
            hint = "What's on your mind?",
            buttonLabel = "Save log",
            onDismiss = { showLogDialog = false },
            onSave = { value ->
                val time = LocalTime.now().format(
                    DateTimeFormatter.ofPattern("HH:mm")
                )

                scope.launch {
                    context.dataStore.edit { preferences ->
                        val current = decodeLogs(preferences[LOGS_KEY])
                        preferences[LOGS_KEY] = encodeLogs(
                            listOf(JournalItem(time, value)) + current
                        )
                    }
                }
                showLogDialog = false
            }
        )
    }


    if (showSearchDialog) {
        val installedApps = remember(context) {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            context.packageManager
                .queryIntentActivities(launcherIntent, 0)
                .map { resolveInfo ->
                    LauncherApp(
                        name = resolveInfo.loadLabel(
                            context.packageManager
                        ).toString(),
                        packageName = resolveInfo.activityInfo.packageName
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
        }

        val matchingApps = installedApps.filter {
            it.name.contains(searchQuery.trim(), ignoreCase = true)
        }

        AlertDialog(
            onDismissRequest = {
                showSearchDialog = false
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

                    Spacer(Modifier.height(12.dp))

                    if (matchingApps.isEmpty()) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                "No apps found."
                            } else {
                                "No matching apps."
                            },
                            color = Muted
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                        ) {
                            itemsIndexed(matchingApps) { _, app ->
                                TextButton(
                                    onClick = {
                                        val launchIntent =
                                            context.packageManager
                                                .getLaunchIntentForPackage(
                                                    app.packageName
                                                )

                                        if (launchIntent != null) {
                                            launchIntent.addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK
                                            )
                                            context.startActivity(launchIntent)
                                            showSearchDialog = false
                                            searchQuery = ""
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = app.name,
                                        color = Ink,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSearchDialog = false
                        searchQuery = ""
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }

    if (logToDelete != null) {
        AlertDialog(
            onDismissRequest = { logToDelete = null },
            title = { Text("Delete journal entry?") },
            text = {
                Text("This journal entry will be permanently deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedLog = logToDelete

                        if (selectedLog != null) {
                            scope.launch {
                                context.dataStore.edit { preferences ->
                                    val currentLogs =
                                        decodeLogs(preferences[LOGS_KEY])

                                    val updatedLogs = currentLogs.toMutableList()
                                    val index = updatedLogs.indexOfFirst {
                                        it.time == selectedLog.time &&
                                                it.text == selectedLog.text
                                    }

                                    if (index >= 0) {
                                        updatedLogs.removeAt(index)
                                        preferences[LOGS_KEY] =
                                            encodeLogs(updatedLogs)
                                    }
                                }
                            }
                        }

                        logToDelete = null
                    }
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { logToDelete = null }) {
                    Text("CANCEL")
                }
            }
        )
    }

}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Rule)
    )
}

@Composable
private fun AddTextDialog(
    title: String,
    hint: String,
    buttonLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(hint) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onSave(value.trim()) }
            ) {
                Text(buttonLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
