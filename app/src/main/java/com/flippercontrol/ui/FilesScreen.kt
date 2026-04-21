package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FsFile
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("/ext") }
    var entries by remember { mutableStateOf<List<FsFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<FsFile?>(null) }

    fun loadPath(path: String) {
        scope.launch {
            isLoading = true
            statusText = "Загрузка $path..."
            try {
                val files = session.listStorage(path)
                entries = files.sortedWith(compareByDescending<FsFile> { it.isDir }.thenBy { it.name })
                currentPath = path
                statusText = "${entries.size} элементов"
            } catch (e: Exception) {
                statusText = "Ошибка: ${e.message}"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadPath("/ext") }

    // Confirm-delete dialog
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text("Удалить?", color = FlipperTheme.red,
                    fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(target.name, color = FlipperTheme.textPrimary,
                    fontFamily = FlipperTheme.mono)
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        statusText = "Удаление ${target.name}..."
                        val ok = session.deleteFile("$currentPath/${target.name}", target.isDir)
                        statusText = if (ok) "Удалено" else "Ошибка удаления"
                        if (ok) loadPath(currentPath)
                    }
                }) {
                    Text("УДАЛИТЬ", color = FlipperTheme.red, fontFamily = FlipperTheme.mono)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("ОТМЕНА", color = FlipperTheme.textSecondary, fontFamily = FlipperTheme.mono)
                }
            },
            containerColor = FlipperTheme.surface,
            tonalElevation = 0.dp
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
                fontFamily = FlipperTheme.mono,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = FlipperTheme.blue, strokeWidth = 2.dp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Up button
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

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries, key = { it.name }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (entry.isDir) loadPath("$currentPath/${entry.name}") }
                        .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = if (entry.isDir) "📁" else when {
                        entry.name.endsWith(".sub")  -> "📡"
                        entry.name.endsWith(".nfc")  -> "💳"
                        entry.name.endsWith(".rfid") -> "🔑"
                        entry.name.endsWith(".ir")   -> "🔴"
                        entry.name.endsWith(".txt")  -> "📄"
                        else -> "📎"
                    }
                    Text(icon, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            color = if (entry.isDir) FlipperTheme.blue else FlipperTheme.textPrimary,
                            fontSize = 13.sp, fontFamily = FlipperTheme.mono,
                            fontWeight = if (entry.isDir) FontWeight.Bold else FontWeight.Normal
                        )
                        if (entry.size > 0) {
                            Text(
                                formatSize(entry.size),
                                color = FlipperTheme.textSecondary,
                                fontSize = 10.sp, fontFamily = FlipperTheme.mono
                            )
                        }
                    }
                    if (entry.isDir) {
                        Text("›", color = FlipperTheme.textSecondary, fontSize = 18.sp,
                            modifier = Modifier.padding(start = 8.dp))
                    } else {
                        // Delete button for files
                        Box(
                            Modifier
                                .clickable { pendingDelete = entry }
                                .background(FlipperTheme.redDim, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("✕", color = FlipperTheme.red,
                                fontSize = 11.sp, fontFamily = FlipperTheme.mono)
                        }
                    }
                }
            }

            if (entries.isEmpty() && !isLoading) {
                item { EmptyState("Папка пуста") }
            }
        }

        Spacer(Modifier.weight(1f))
        if (statusText.isNotEmpty()) {
            Text(statusText, color = FlipperTheme.textSecondary,
                fontSize = 10.sp, fontFamily = FlipperTheme.mono)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
}
