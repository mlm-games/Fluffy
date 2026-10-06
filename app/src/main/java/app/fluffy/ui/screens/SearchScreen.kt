package app.fluffy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluffy.R
import app.fluffy.search.SearchHit
import app.fluffy.ui.components.FileListRow
import app.fluffy.ui.components.rowModelForSearchHit
import app.fluffy.viewmodel.SearchViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchScreen(
    scopeLabel: String,
    onBack: () -> Unit,
    onOpenResult: (SearchHit) -> Unit,
    viewModel: SearchViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
        keyboard?.show()
    }

    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_recursive_hint, scopeLabel)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(fieldFocus)
                        .focusProperties {
                            right = backFocus
                            down = backFocus
                        }
                )
                if (state.isSearching) {
                    IconButton(onClick = viewModel::cancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
                IconButton(
                    onClick = {
                        keyboard?.hide()
                        viewModel.cancel()
                        onBack()
                    },
                    modifier = Modifier
                        .focusRequester(backFocus)
                        .focusProperties { left = fieldFocus }
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
        }

        if (state.isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )
        } else {
            Spacer(Modifier.fillMaxWidth().height(4.dp))
        }

        if (state.hits.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when {
                            !state.hasQuery -> stringResource(R.string.search_type_to_begin)
                            state.isSearching -> stringResource(R.string.search_searching)
                            else -> stringResource(R.string.search_no_matches)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.hits, key = { it.uri.toString() }) { hit ->
                    FileListRow(
                        model = rowModelForSearchHit(hit),
                        selected = false,
                        hasSelection = false,
                        showFileCount = false,
                        onToggleSelect = {},
                        onOpenDir = { onOpenResult(hit) },
                        onOpenArchive = { onOpenResult(hit) },
                        onOpenContent = { _, _ -> onOpenResult(hit) },
                        onOpenWith = { _, _ -> onOpenResult(hit) },
                        onClick = { onOpenResult(hit) },
                        onExtractHere = null
                    )
                }
            }
        }

        if (state.truncated) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.search_truncated,
                        state.hits.size, state.hits.size, state.visitedDirs
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
