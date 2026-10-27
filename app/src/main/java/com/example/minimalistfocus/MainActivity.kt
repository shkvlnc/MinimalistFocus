package com.example.minimalistfocus

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

private val Paper = Color(0xFFEDEAE3)
private val Ink = Color(0xFF242424)
private val MutedInk = Color(0xFF85837D)
private val LightLine = Color(0xFFD2CFC7)

private const val PREFS = "minimalist_focus_v3"
private const val DATA_KEY = "journal_lines" // legacy key from older versions
private const val LEGACY_ARCHIVE_KEY = "migrated_legacy_logs"
private const val MIGRATION_DONE_KEY = "journal_migration_v1_done"
private const val DAY_KEY_PREFIX = "journal_day_"
private const val FOLDERS_KEY = "journal_folders_v1"
private const val DARK_THEME_KEY = "dark_theme_enabled"
private const val BOOKS_KEY = "epub_books_v1"

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

private data class EpubBook(
    val id: String,
    val title: String,
    val author: String,
    val directory: String,
    val chapters: List<String>,
    val currentChapter: Int = 0
)

private fun loadBooks(prefs: android.content.SharedPreferences): List<EpubBook> = runCatching {
    val array = JSONArray(prefs.getString(BOOKS_KEY, "[]"))
    (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        val paths = obj.optJSONArray("chapters") ?: JSONArray()
        EpubBook(
            id = obj.optString("id"), title = obj.optString("title", "Untitled book"),
            author = obj.optString("author", "Unknown author"), directory = obj.optString("directory"),
            chapters = (0 until paths.length()).map { paths.optString(it) },
            currentChapter = obj.optInt("currentChapter", 0).coerceAtLeast(0)
        )
    }
}.getOrDefault(emptyList())

private fun saveBooks(prefs: android.content.SharedPreferences, books: List<EpubBook>) {
    val array = JSONArray()
    books.forEach { book -> array.put(JSONObject().apply {
        put("id", book.id); put("title", book.title); put("author", book.author)
        put("directory", book.directory); put("currentChapter", book.currentChapter)
        put("chapters", JSONArray().apply { book.chapters.forEach { put(it) } })
    }) }
    prefs.edit().putString(BOOKS_KEY, array.toString()).apply()
}

private fun importEpub(context: android.content.Context, uri: Uri): EpubBook {
    val root = File(context.filesDir, "books").apply { mkdirs() }
    val id = UUID.randomUUID().toString()
    val directory = File(root, id).apply { mkdirs() }
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open this file." }
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(directory, entry.name).canonicalFile
                    require(out.path.startsWith(directory.canonicalPath + File.separator) || out == directory) { "Invalid EPUB path." }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val container = File(directory, "META-INF/container.xml")
        require(container.exists()) { "This file does not look like a valid EPUB." }
        val containerText = container.readText()
        val opfRelative = Regex("full-path\\s*=\\s*['\"]([^'\"]+)['\"]").find(containerText)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("EPUB package file was not found.")
        val opfFile = File(directory, opfRelative)
        require(opfFile.exists()) { "EPUB package file is missing." }
        val opfText = opfFile.readText()
        val base = opfFile.parentFile ?: directory
        val manifest = linkedMapOf<String, String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(opfText.reader())
        var title = "Untitled book"
        var author = "Unknown author"
        var spineIds = mutableListOf<String>()
        var inTitle = false
        var inCreator = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "item" -> {
                        val itemId = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (!itemId.isNullOrBlank() && !href.isNullOrBlank()) manifest[itemId] = href
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { spineIds.add(it) }
                    "title" -> inTitle = true
                    "creator" -> inCreator = true
                }
                XmlPullParser.TEXT -> {
                    if (inTitle && parser.text.isNotBlank()) title = parser.text.trim()
                    if (inCreator && parser.text.isNotBlank()) author = parser.text.trim()
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "title" -> inTitle = false
                    "creator" -> inCreator = false
                }
            }
            event = parser.next()
        }
        val chapterPaths = spineIds.mapNotNull { manifest[it] }.map { href ->
            val decoded = Uri.decode(href.substringBefore('#'))
            val file = File(base, decoded).canonicalFile
            require(file.path.startsWith(directory.canonicalPath + File.separator)) { "Invalid chapter path." }
            file.relativeTo(directory).invariantSeparatorsPath
        }.filter { File(directory, it).exists() }
        require(chapterPaths.isNotEmpty()) { "No readable chapters were found in this EPUB." }
        return EpubBook(id, title.ifBlank { "Untitled book" }, author.ifBlank { "Unknown author" }, directory.absolutePath, chapterPaths)
    } catch (e: Exception) {
        directory.deleteRecursively()
        throw e
    }
}

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

    fun loadLines(storageKey: String): MutableList<NoteLine> {
        val result = mutableListOf<NoteLine>()
        val raw = prefs.getString(storageKey, null)
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
        if (result.isEmpty()) result.add(newNoteLine().copy(hasBullet = false))
        return result
    }

    val todayKey = DAY_KEY_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    // One-time, non-destructive migration: preserve the old undated journal as a separate archive.
    remember {
        if (!prefs.getBoolean(MIGRATION_DONE_KEY, false)) {
            val oldRaw = prefs.getString(DATA_KEY, null)
            if (!oldRaw.isNullOrBlank() && prefs.getString(LEGACY_ARCHIVE_KEY, null).isNullOrBlank()) {
                prefs.edit().putString(LEGACY_ARCHIVE_KEY, oldRaw).apply()
            }
            prefs.edit().putBoolean(MIGRATION_DONE_KEY, true).apply()
        }
        if (prefs.getString(todayKey, null).isNullOrBlank()) {
            prefs.edit().putString(todayKey, JSONArray().put(JSONObject().apply {
                put("id", System.nanoTime()); put("time", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                put("text", ""); put("status", LineStatus.NOTE.name); put("indent", 0)
                put("hasBullet", false); put("isBold", false); put("showTimestamp", false)
            }).toString()).apply()
        }
        true
    }
    var activeJournalKey by remember { mutableStateOf(todayKey) }
    var isDarkTheme by remember { mutableStateOf(prefs.getBoolean(DARK_THEME_KEY, false)) }
    val paper = if (isDarkTheme) Color(0xFF202020) else Paper
    val ink = if (isDarkTheme) Color(0xFFECEBE6) else Ink
    val mutedInk = if (isDarkTheme) Color(0xFFAAA9A2) else MutedInk
    val lightLine = if (isDarkTheme) Color(0xFF44443F) else LightLine

    var lines by remember { mutableStateOf(loadLines(todayKey)) }
    var editMode by remember { mutableStateOf(lines.size == 1 && lines.firstOrNull()?.text.isNullOrBlank()) }
    var activeLineId by remember { mutableLongStateOf(lines.firstOrNull()?.id ?: 0L) }
    var statusLineId by remember { mutableStateOf<Long?>(null) }
    var showAppSearch by remember { mutableStateOf(false) }
    var showLibraryDrawer by remember { mutableStateOf(false) }
    var showMigratedLogs by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var folders by remember {
        mutableStateOf(runCatching {
            val arr = JSONArray(prefs.getString(FOLDERS_KEY, "[]"))
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList()))
    }
    var openedArchiveTitle by remember { mutableStateOf<String?>(null) }
    var currentScreen by remember { mutableIntStateOf(0) } // 0 journal, 1 books/reader
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var books by remember { mutableStateOf(loadBooks(prefs)) }
    var readerBookId by remember { mutableStateOf<String?>(null) }
    var readerChapter by remember { mutableIntStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            isImporting = true
            val result = runCatching { withContext(Dispatchers.IO) { importEpub(context, uri) } }
            result.onSuccess { book ->
                books = books + book
                saveBooks(prefs, books)
                readerBookId = book.id
                readerChapter = 0
                currentScreen = 1
            }.onFailure { importError = it.message ?: "Could not import this EPUB." }
            isImporting = false
        }
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
        prefs.edit().putString(activeJournalKey, array.toString()).apply()
    }
    fun openStoredJournal(key: String) {
        activeJournalKey = key
        lines = loadLines(key)
        if (lines.size == 1 && lines.first().text.isBlank()) {
            lines = lines.map { it.copy(hasBullet = false) }.toMutableList()
            activeLineId = lines.first().id
            editMode = true
        } else {
            activeLineId = lines.firstOrNull()?.id ?: 0L
            editMode = false
        }
        saveLines(lines)
        showLibraryDrawer = false
    }
    fun saveFolders(updated: List<String>) {
        folders = updated
        val array = JSONArray()
        updated.forEach { array.put(it) }
        prefs.edit().putString(FOLDERS_KEY, array.toString()).apply()
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
                    Text("☰", fontSize = 24.sp, color = ink,
                        modifier = Modifier.clickable { showLibraryDrawer = true }.padding(end = 13.dp, top = 8.dp, bottom = 8.dp))
                    Column(Modifier.weight(1f)) {
                        val isTodayJournal = activeJournalKey == todayKey
                        Text(if (isTodayJournal) "Journal" else (openedArchiveTitle ?: "Journal"), color = ink, fontSize = 25.sp, fontWeight = FontWeight.Medium)
                        Text(if (isTodayJournal) SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()) else "Saved journal · read or edit", color = mutedInk, fontSize = 12.sp)
                    }
                    Text("◷", fontSize = 23.sp, color = ink,
                        modifier = Modifier.clickable { showHistory = true }.padding(horizontal = 7.dp, vertical = 10.dp))
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
                        LaunchedEffect(editMode, line.id) {
                            if (editMode && line.id == lines.firstOrNull()?.id && line.text.isBlank()) {
                                requester.requestFocus()
                            }
                        }
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
                                    if (line.hasBullet && line.text.isNotBlank()) Text(
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
            val selectedBook = books.firstOrNull { it.id == readerBookId }
            BooksScreen(
                paper = paper, ink = ink, mutedInk = mutedInk, lightLine = lightLine,
                books = books, selectedBook = selectedBook, chapterIndex = readerChapter,
                importing = isImporting,
                onImport = { epubPicker.launch(arrayOf("*/*")) },
                onOpen = { book -> readerBookId = book.id; readerChapter = book.currentChapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0)) },
                onBackToLibrary = { readerBookId = null },
                onBackToJournal = { currentScreen = 0 },
                onChapterChange = { index ->
                    readerChapter = index.coerceIn(0, (selectedBook?.chapters?.size ?: 1) - 1)
                    if (selectedBook != null) {
                        books = books.map { if (it.id == selectedBook.id) it.copy(currentChapter = readerChapter) else it }
                        saveBooks(prefs, books)
                    }
                },
                onDelete = { book ->
                    File(book.directory).deleteRecursively()
                    books = books.filterNot { it.id == book.id }
                    saveBooks(prefs, books)
                    if (readerBookId == book.id) readerBookId = null
                }
            )
        }
    }

    if (showLibraryDrawer) {
        AlertDialog(
            onDismissRequest = { showLibraryDrawer = false }, containerColor = paper,
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Library", color = ink, fontSize = 23.sp, modifier = Modifier.weight(1f))
                Text("×", color = ink, fontSize = 26.sp, modifier = Modifier.clickable { showLibraryDrawer = false }.padding(horizontal = 8.dp))
            } },
            text = { Column(Modifier.fillMaxWidth()) {
                LibraryRow("Today", "Current journal", "▤", ink, mutedInk) { openedArchiveTitle = null; openStoredJournal(todayKey) }
                LibraryRow("Migrated Logs", "Original entries · undated", "▣", ink, mutedInk) { showLibraryDrawer = false; showMigratedLogs = true }
                LibraryRow("Journal History", "Browse previous days", "◷", ink, mutedInk) { showLibraryDrawer = false; showHistory = true }
                LibraryRow("Books", "EPUB reading collection", "▤", ink, mutedInk) { showLibraryDrawer = false; readerBookId = null; currentScreen = 1 }
                Spacer(Modifier.height(12.dp)); HorizontalDivider(color = lightLine); Spacer(Modifier.height(10.dp))
                Text("COLLECTIONS", color = mutedInk, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 6.dp))
                if (folders.isEmpty()) Text("No folders yet", color = mutedInk, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                folders.forEach { folder ->
                    LibraryRow(folder, "Collection folder", "▰", ink, mutedInk) { showLibraryDrawer = false; openedArchiveTitle = folder; showHistory = false; openStoredJournal("folder_${folder.hashCode()}") }
                }
                LibraryRow("New folder", "Create a collection", "+", ink, mutedInk) { newFolderName = ""; showLibraryDrawer = false; showNewFolder = true }
            } },
            confirmButton = { TextButton(onClick = { showLibraryDrawer = false }) { Text("Close", color = ink) } }
        )
    }
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false }, containerColor = paper,
            title = { Text("New folder", color = ink) },
            text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = { TextButton(onClick = {
                val name = newFolderName.trim()
                if (name.isNotEmpty() && folders.none { it.equals(name, ignoreCase = true) }) saveFolders(folders + name)
                showNewFolder = false
            }) { Text("Create", color = ink) } },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel", color = ink) } }
        )
    }
    if (showMigratedLogs) {
        val legacyLines = remember(showMigratedLogs) { parseStoredLines(prefs.getString(LEGACY_ARCHIVE_KEY, null)) }
        AlertDialog(
            onDismissRequest = { showMigratedLogs = false }, containerColor = paper,
            title = { Text("Migrated Logs", color = ink) },
            text = { if (legacyLines.isEmpty()) Text("No older entries were found.", color = mutedInk) else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(legacyLines, key = { index, line -> "${line.id}_$index" }) { _, line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        if (line.hasBullet) Text(line.status.symbol, color = ink, modifier = Modifier.width(22.dp))
                        Text(line.text.ifBlank { "(empty entry)" }, color = ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (line.showTimestamp) Text(line.time, color = mutedInk, fontSize = 10.sp)
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { showMigratedLogs = false }) { Text("Close", color = ink) } }
        )
    }
    if (showHistory) {
        val dayKeys = remember(showHistory, prefs.all.keys.size) { prefs.all.keys.filter { it.startsWith(DAY_KEY_PREFIX) }.sortedDescending() }
        AlertDialog(
            onDismissRequest = { showHistory = false }, containerColor = paper,
            title = { Text("Journal History", color = ink) },
            text = { if (dayKeys.isEmpty()) Text("No saved days yet.", color = mutedInk) else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(dayKeys, key = { _, key -> key }) { _, key ->
                    val datePart = key.removePrefix(DAY_KEY_PREFIX)
                    val label = runCatching { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(datePart)!!) }.getOrDefault(datePart)
                    val count = parseStoredLines(prefs.getString(key, null)).count { it.text.isNotBlank() }
                    LibraryRow(label, "$count entries", "◷", ink, mutedInk) {
                        openedArchiveTitle = label
                        showHistory = false
                        openStoredJournal(key)
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close", color = ink) } }
        )
    }

    val selectedStatusLine = lines.firstOrNull { it.id == statusLineId }
    if (editMode && selectedStatusLine != null) AlertDialog(
        onDismissRequest = { statusLineId = null }, containerColor = paper,
        title = { Text("Change line status", color = ink) },
        text = { Column { LineStatus.values().forEach { status -> Text("${status.symbol}   ${status.label}", color = ink, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().clickable { updateLine(selectedStatusLine.id) { it.copy(status = status) }; statusLineId = null }.padding(vertical = 9.dp)) } } },
        confirmButton = { TextButton(onClick = { statusLineId = null }) { Text("Cancel", color = ink) } }
    )
    if (importError != null) AlertDialog(
        onDismissRequest = { importError = null }, containerColor = paper,
        title = { Text("Could not import book", color = ink) },
        text = { Text(importError ?: "", color = ink) },
        confirmButton = { TextButton(onClick = { importError = null }) { Text("OK", color = ink) } }
    )
    if (showAppSearch) AppSearchDialog(paper, ink, onDismiss = { showAppSearch = false }, onLaunch = { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it) }
        showAppSearch = false
    })
}

@Composable
private fun LibraryRow(title: String, subtitle: String, symbol: String, ink: Color, mutedInk: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, color = ink, fontSize = 21.sp, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ink, fontSize = 15.sp)
            Text(subtitle, color = mutedInk, fontSize = 11.sp)
        }
        Text("›", color = mutedInk, fontSize = 22.sp)
    }
}

private fun parseStoredLines(raw: String?): List<NoteLine> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            NoteLine(
                id = obj.optLong("id", System.nanoTime() + i), time = obj.optString("time", ""),
                text = obj.optString("text", ""),
                status = runCatching { LineStatus.valueOf(obj.optString("status", "NOTE")) }.getOrDefault(LineStatus.NOTE),
                indent = obj.optInt("indent", 0).coerceIn(0, 8), hasBullet = obj.optBoolean("hasBullet", true),
                isBold = obj.optBoolean("isBold", false), showTimestamp = obj.optBoolean("showTimestamp", false)
            )
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun BooksScreen(
    paper: Color, ink: Color, mutedInk: Color, lightLine: Color,
    books: List<EpubBook>, selectedBook: EpubBook?, chapterIndex: Int, importing: Boolean,
    onImport: () -> Unit, onOpen: (EpubBook) -> Unit, onBackToLibrary: () -> Unit,
    onBackToJournal: () -> Unit, onChapterChange: (Int) -> Unit, onDelete: (EpubBook) -> Unit
) {
    var showDeleteId by remember { mutableStateOf<String?>(null) }
    val chapterPath = selectedBook?.chapters?.getOrNull(chapterIndex)
    Column(Modifier.fillMaxSize().background(paper).padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = ink, fontSize = 29.sp, modifier = Modifier.clickable {
                if (selectedBook != null) onBackToLibrary() else onBackToJournal()
            }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp))
            Column(Modifier.weight(1f)) {
                Text(selectedBook?.title ?: "Books", color = ink, fontSize = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(if (selectedBook != null) selectedBook.author else "EPUB collection", color = mutedInk, fontSize = 12.sp, maxLines = 1)
            }
            if (selectedBook == null) Text("+", color = ink, fontSize = 28.sp,
                modifier = Modifier.clickable(onClick = onImport).padding(horizontal = 8.dp))
        }
        Spacer(Modifier.height(10.dp)); HorizontalDivider(color = lightLine)
        if (selectedBook != null && chapterPath != null) {
            Spacer(Modifier.height(8.dp))
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        webViewClient = WebViewClient()

                        setBackgroundColor(
                            android.graphics.Color.rgb(237, 234, 227)
                        )
                    }
                },
                update = { webView ->
                    val chapterFile = File(selectedBook.directory, chapterPath)

                    if (chapterFile.exists()) {
                        val html = chapterFile.readText()

                        val readerCss = """
                <style>
                    body {
                        background: #EDEAE3;
                        color: #242424;
                        font-family: serif;
                        font-size: 18px;
                        line-height: 1.65;
                        padding: 8px 5px 30px;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                    }
                    table {
                        max-width: 100%;
                    }
                    a {
                        color: #242424;
                    }
                </style>
            """.trimIndent()

                        val headRegex = Regex("""(?i)<head[^>]*>""")

                        val styled = if (headRegex.containsMatchIn(html)) {
                            val headMatch = headRegex.find(html)
                            if (headMatch != null) {
                                html.replaceRange(
                                    headMatch.range,
                                    headMatch.value + readerCss
                                )
                            } else {
                                html
                            }
                        } else {
                            """
    <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            $readerCss
        </head>
        <body>$html</body>
    </html>
    """.trimIndent()
                        }

                        webView.loadDataWithBaseURL(
                            chapterFile.parentFile?.toURI()?.toString(),
                            styled,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${chapterIndex + 1} / ${selectedBook.chapters.size}", color = mutedInk, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹ Previous", color = if (chapterIndex > 0) ink else mutedInk, modifier = Modifier.clickable(enabled = chapterIndex > 0) { onChapterChange(chapterIndex - 1) }.padding(10.dp))
                    Text("Next ›", color = if (chapterIndex < selectedBook.chapters.lastIndex) ink else mutedInk,
                        modifier = Modifier.clickable(enabled = chapterIndex < selectedBook.chapters.lastIndex) { onChapterChange(chapterIndex + 1) }.padding(10.dp))
                }
            }
        } else {
            if (books.isEmpty()) {
                Spacer(Modifier.weight(1f))
                Text("No books yet", color = ink, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Text("Import an EPUB file to read offline.", color = mutedInk, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(18.dp))
                Text("＋  IMPORT EPUB", color = ink, fontSize = 13.sp, letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).border(1.dp, lightLine, RoundedCornerShape(8.dp)).clickable(onClick = onImport).padding(horizontal = 18.dp, vertical = 13.dp))
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(books, key = { _, book -> book.id }) { _, book ->
                        Row(Modifier.fillMaxWidth().clickable { onOpen(book) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("▤", color = ink, fontSize = 24.sp, modifier = Modifier.width(38.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, color = ink, fontSize = 16.sp, maxLines = 2)
                                Text("${book.author} · chapter ${(book.currentChapter + 1).coerceAtMost(book.chapters.size)} of ${book.chapters.size}", color = mutedInk, fontSize = 12.sp)
                            }
                            Text("⋯", color = mutedInk, fontSize = 22.sp, modifier = Modifier.clickable { showDeleteId = book.id }.padding(start = 10.dp))
                        }
                        HorizontalDivider(color = lightLine)
                    }
                }
                Text(if (importing) "IMPORTING…" else "+  IMPORT EPUB", color = ink, fontSize = 12.sp, letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable(enabled = !importing, onClick = onImport).padding(12.dp))
            }
        }
        Text("SWIPE LEFT TO JOURNAL", color = mutedInk, fontSize = 10.sp, letterSpacing = 1.3.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 4.dp))
    }
    val deleteBook = books.firstOrNull { it.id == showDeleteId }
    if (deleteBook != null) AlertDialog(
        onDismissRequest = { showDeleteId = null }, containerColor = paper,
        title = { Text("Remove book?", color = ink) },
        text = { Text("${deleteBook.title} will be removed from this device.", color = ink) },
        confirmButton = { TextButton(onClick = { onDelete(deleteBook); showDeleteId = null }) { Text("Remove", color = ink) } },
        dismissButton = { TextButton(onClick = { showDeleteId = null }) { Text("Cancel", color = ink) } }
    )
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

