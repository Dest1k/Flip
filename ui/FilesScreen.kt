package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.flippercontrol.core.FlipperRpcSession
import kotlinx.coroutines.launch

data class FsEntry(val name: String, val isDir: Boolean, val size: Long = 0L)

@Composable
fun FilesScreen(
    session: FlipperRpcSession,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("/ext") }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    fun loadPath(path: String) {
        scope.launch {
            isLoading = true
            statusText = "Загрузка $path..."
            try {
                val names = session.listStorage(path)
                entries = names.map { name ->
                    val isDir = !name.contains(".")
                    FsEntry(name, isDir)
                }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name })
                currentPath = path
                statusText = "${entries.size} элементов"
            } catch (e: Exception) {
                statusText = "Ошибка: ${e.message}"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadPath("/ext") }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlipperTheme.bg)
            .padding(16.dp)
    ) {
        TopBar(title = "SD КАРТА", color = FlipperTheme.blue, onBack = onBack)

        // Path breadcrumb
        Row(
            Modifier
                .fillMaxWidth()
                .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Text(
                "📁 $currentPath",
                color = FlipperTheme.blue, fontSize = 12.sp,
                fontFamily = FlipperTheme.mono,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = FlipperTheme.blue,
                    strokeWidth = 2.dp
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
                androidx.compose.material3.Text("⬆  ..", color = FlipperTheme.textSecondary,
                     fontSize = 13.sp, fontFamily = FlipperTheme.mono)
            }
            Spacer(Modifier.height(4.dp))
        }

        // File list
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDir) loadPath("$currentPath/${entry.name}")
                        }
                        .background(FlipperTheme.surface, RoundedCornerShape(8.dp))
                        .padding(12.dp),
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
                    androidx.compose.material3.Text(icon, fontSize = 16.sp,
                         modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(
                            entry.name,
                            color = if (entry.isDir) FlipperTheme.blue else FlipperTheme.textPrimary,
                            fontSize = 13.sp, fontFamily = FlipperTheme.mono,
                            fontWeight = if (entry.isDir) FontWeight.Bold else FontWeight.Normal
                        )
                        if (!entry.isDir && entry.size > 0) {
                            androidx.compose.material3.Text(
                                "${entry.size} bytes",
                                color = FlipperTheme.textSecondary,
                                fontSize = 10.sp, fontFamily = FlipperTheme.mono
                            )
                        }
                    }
                    if (entry.isDir) {
                        androidx.compose.material3.Text("›", color = FlipperTheme.textSecondary, fontSize = 18.sp)
                    }
                }
            }

            if (entries.isEmpty() && !isLoading) {
                item { EmptyState("Папка пуста") }
            }
        }

        Spacer(Modifier.weight(1f))
        androidx.compose.material3.Text(statusText, color = FlipperTheme.textSecondary,
             fontSize = 10.sp, fontFamily = FlipperTheme.mono)
    }
}
