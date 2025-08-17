package app.fluffy.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

data class BrowserUi(
    val canGoUp: Boolean,
    val path: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    state: app.fluffy.viewmodel.FileBrowserState,
    onPickRoot: () -> Unit,
    onOpenDir: (Uri) -> Unit,
    onBack: () -> Unit,
    onExtractArchive: (Uri, Uri) -> Unit,
    onCreateZip: (List<Uri>, String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit
) {
    val currentDir = state.currentDir
    val canUp = (state.stack.size > 1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.currentDir?.toString() ?: "Pick a folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (canUp) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Up") }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenTasks) { Icon(Icons.Default.Archive, contentDescription = "Tasks") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    IconButton(onClick = onPickRoot) { Icon(Icons.Default.MoreVert, contentDescription = "Pick root") }
                }
            )
        }
    ) { pv ->
        if (currentDir == null) {
            Box(Modifier.fillMaxSize().padding(pv), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Button(onClick = onPickRoot) { Text("Pick a folder") }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pv),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.items, key = { it.uri.toString() }) { df ->
                FileRow(df,
                    onOpenDir = onOpenDir,
                    onExtractHere = { onExtractArchive(df.uri, currentDir) }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    df: DocumentFile,
    onOpenDir: (Uri) -> Unit,
    onExtractHere: () -> Unit
) {
    val isArchive = remember(df.name) {
        val n = (df.name ?: "").lowercase()
        n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz")
                || n.endsWith(".tbz2") || n.endsWith(".tar.bz2") || n.endsWith(".txz") || n.endsWith(".tar.xz")
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (df.isDirectory) onOpenDir(df.uri) else if (isArchive) onExtractHere()
        }
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (df.isDirectory) Icons.Default.Folder else Icons.Default.Archive, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(df.name ?: "(no name)", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = buildString {
                    if (df.isDirectory) append("Folder")
                    else append(df.type ?: "file")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}