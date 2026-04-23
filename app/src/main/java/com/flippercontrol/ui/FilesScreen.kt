package com.flippercontrol.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FsFile
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FilesScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentPath by remember { mutableStateOf("/ext") }
    var entries by remember { mutableStateOf<List<FsFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<FsFile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val addLog = { text: String, level: LogLevel -> log = buildLog(log, text, level) }

    fun loadPath(path: String) {
        scope.launch {
            isLoading = true
            statusText = "Загрузка $path..."
            addLog("Список: $path", LogLevel.INFO)
            selectedEntry = null
            try {
                entries = session.listStorage(path)
                    .sortedWith(compareByDescending<FsFile> { it.isDir }.thenBy { it.name })
                currentPath = path
                statusText = if (entries.isEmpty()) "Папка пуста" else "${entries.size} элементов"
                addLog(if (entries.isEmpty()) "Папка пуста" else "Найдено: ${entries.size} элементов", LogLevel.OK)
            } catch (e: Exception) {
                statusText = "Ошибка: ${e.message}"
                addLog("Ошибка: ${e.message}", LogLevel.ERROR)
            }
            isLoading = false
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true
            var uploadOk = false
            try {
                val fileName = getDisplayName(context, uri) ?: "upload"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Не удалось прочитать файл")
                addLog("Загрузка: $fileName (${formatSize(bytes.size.toLong())})", LogLevel.INFO)
                val ok = session.writeFile("$currentPath/$fileName", bytes)
                statusText = if (ok) "✓ Загружено: $fileName" else "✗ Ошибка загрузки"
                addLog(if (ok) "Загружено: $fileName" else "Ошибка записи на Flipper", if (ok) LogLevel.OK else LogLevel.ERROR)
                uploadOk = ok
            } catch (e: Exception) {
                statusText = "Ошибка: ${e.message}"
                addLog("Ошибка загрузки: ${e.message}", LogLevel.ERROR)
            } finally {
                if (uploadOk) {
                    loadPath(currentPath)  // loadPath manages isLoading itself
                } else {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadPath("/ext") }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        val entry = selectedEntry
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = FlipperTheme.surface,
            tonalElevation = 0.dp,
            title = {
                Text("Удалить?", color = FlipperTheme.red,
                    fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Удалить ${entry?.name}?\nДействие необратимо.",
                    color = FlipperTheme.textPrimary, fontFamily = FlipperTheme.mono
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    entry ?: return@TextButton
                    scope.launch {
                        statusText = "Удаление ${entry.name}..."
                        addLog("Удаление: ${entry.name}", LogLevel.WARN)
                        isLoading = true
                        val ok = session.deleteFile("$currentPath/${entry.name}")
                        statusText = if (ok) "✓ Удалено: ${entry.name}" else "✗ Ошибка удаления"
                        addLog(if (ok) "Удалено: ${entry.name}" else "Ошибка удаления", if (ok) LogLevel.OK else LogLevel.ERROR)
                        if (ok) loadPath(currentPath)
                        else isLoading = false
                    }
                }) { Text("УДАЛИТЬ", color = FlipperTheme.red, fontFamily = FlipperTheme.mono) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("ОТМЕНА", color = FlipperTheme.textSecondary, fontFamily = FlipperTheme.mono)
                }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "SD КАРТА", color = FlipperTheme.blue, onBack = onBack)

        // Path bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📁 $currentPath",
                color = FlipperTheme.blue, fontSize = 12.sp,
                fontFamily = FlipperTheme.mono, modifier = Modifier.weight(1f)
            )
            if (isLoading) CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = FlipperTheme.blue, strokeWidth = 2.dp
            )
        }

        Spacer(Modifier.height(8.dp))

        // Action toolbar
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("↺", FlipperTheme.textSecondary, modifier = Modifier.width(44.dp)) {
                loadPath(currentPath)
            }
            ActionButton("⬆ ЗАГРУЗИТЬ", FlipperTheme.green, modifier = Modifier.weight(1f)) {
                uploadLauncher.launch("*/*")
            }
            val sel = selectedEntry
            if (sel != null && !sel.isDir) {
                ActionButton("⬇ СКАЧАТЬ", FlipperTheme.blue, modifier = Modifier.weight(1f)) {
                    scope.launch {
                        statusText = "Скачиваю ${sel.name}..."
                        addLog("Скачивание: ${sel.name}", LogLevel.INFO)
                        isLoading = true
                        try {
                            val bytes = session.readFile("$currentPath/${sel.name}")
                            saveToDownloads(context, sel.name, bytes)
                            statusText = "✓ Сохранено в Загрузки: ${sel.name} (${formatSize(bytes.size.toLong())})"
                            addLog("Сохранено в Downloads: ${sel.name}", LogLevel.OK)
                        } catch (e: Exception) {
                            statusText = "✗ Ошибка: ${e.message}"
                            addLog("Ошибка скачивания: ${e.message}", LogLevel.ERROR)
                        }
                        isLoading = false
                    }
                }
                ActionButton("🗑", FlipperTheme.red, modifier = Modifier.width(44.dp)) {
                    showDeleteConfirm = true
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Up navigation
        if (currentPath != "/ext" && currentPath != "/") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val parent = currentPath.substringBeforeLast("/").ifEmpty { "/ext" }
                        loadPath(parent)
                    }
                    .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⬆  ..", color = FlipperTheme.textSecondary,
                    fontSize = 13.sp, fontFamily = FlipperTheme.mono)
            }
            Spacer(Modifier.height(4.dp))
        }

        // File list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(entries, key = { it.name }) { entry ->
                val isSelected = selectedEntry?.name == entry.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDir) {
                                loadPath("$currentPath/${entry.name}")
                            } else {
                                selectedEntry = if (isSelected) null else entry
                            }
                        }
                        .border(
                            if (isSelected) 1.dp else 0.5.dp,
                            if (isSelected) FlipperTheme.blue else FlipperTheme.border,
                            RoundedCornerShape(8.dp)
                        )
                        .background(
                            if (isSelected) FlipperTheme.blueDim else FlipperTheme.surface,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = if (entry.isDir) "📁" else when {
                        entry.name.endsWith(".sub")  -> "📡"
                        entry.name.endsWith(".nfc")  -> "💳"
                        entry.name.endsWith(".rfid") -> "🔑"
                        entry.name.endsWith(".ir")   -> "🔴"
                        entry.name.endsWith(".txt")  -> "📄"
                        entry.name.endsWith(".fap")  -> "⚙"
                        entry.name.endsWith(".ibtn") -> "🔵"
                        else                         -> "📎"
                    }
                    Text(icon, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            color = if (entry.isDir || isSelected) FlipperTheme.blue else FlipperTheme.textPrimary,
                            fontSize = 13.sp, fontFamily = FlipperTheme.mono,
                            fontWeight = if (entry.isDir) FontWeight.Bold else FontWeight.Normal
                        )
                        if (!entry.isDir && entry.size > 0) {
                            Text(
                                formatSize(entry.size),
                                color = FlipperTheme.textSecondary,
                                fontSize = 10.sp, fontFamily = FlipperTheme.mono
                            )
                        }
                    }
                    when {
                        entry.isDir  -> Text("›", color = FlipperTheme.textSecondary, fontSize = 18.sp)
                        isSelected   -> Text("✓", color = FlipperTheme.blue, fontSize = 14.sp)
                    }
                }
            }

            if (entries.isEmpty() && !isLoading) {
                item { EmptyState("Папка пуста") }
            }
        }

        // Status bar
        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(statusText, color = FlipperTheme.textSecondary,
                fontSize = 10.sp, fontFamily = FlipperTheme.mono)
        }

        // Activity log
        Spacer(Modifier.height(6.dp))
        ActivityLogPanel(log, Modifier.fillMaxWidth())
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L     -> "%.1f KB".format(bytes / 1_024.0)
    else                -> "$bytes B"
}

private fun getDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}

private fun saveToDownloads(context: Context, fileName: String, bytes: ByteArray) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Не удалось создать файл в Downloads")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw Exception("Не удалось открыть поток записи")
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        File(dir, fileName).writeBytes(bytes)
    }
}
