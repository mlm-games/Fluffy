package app.fluffy.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.bookmarksDataStore by preferencesDataStore(name = "bookmarks")

@Serializable
data class Bookmark(
    val name: String,
    val path: String,
    val access: String? = null
)

class BookmarksRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        private val BOOKMARKS_KEY = stringPreferencesKey("custom_bookmarks")
    }

    val bookmarks: Flow<List<Bookmark>> = context.bookmarksDataStore.data.map { prefs ->
        val stored = prefs[BOOKMARKS_KEY]?.let {
            runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrElse { emptyList() }
        } ?: emptyList()
        stored
    }

    suspend fun addBookmark(bookmark: Bookmark) {
        val normalized = normalizeBookmark(bookmark) ?: return
        context.bookmarksDataStore.edit { prefs ->
            val current = prefs[BOOKMARKS_KEY]?.let {
                runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()

            val updated = current.filterNot {
                val existingAccess = normalizeAccess(it.access, it.path)
                val existingPath = normalizePath(it.path, existingAccess)
                val targetAccess = normalizeAccess(normalized.access, normalized.path)
                val targetPath = normalizePath(normalized.path, targetAccess)
                existingAccess == targetAccess && existingPath == targetPath
            } + normalized
            prefs[BOOKMARKS_KEY] = json.encodeToString(updated)
        }
    }

    suspend fun removeBookmark(bookmark: Bookmark) {
        context.bookmarksDataStore.edit { prefs ->
            val current = prefs[BOOKMARKS_KEY]?.let {
                runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()

            val targetAccess = normalizeAccess(bookmark.access, bookmark.path)
            val targetPath = normalizePath(bookmark.path, targetAccess)
            val updated = current.filterNot {
                val existingAccess = normalizeAccess(it.access, it.path)
                val existingPath = normalizePath(it.path, existingAccess)
                existingPath == targetPath && existingAccess == targetAccess
            }
            prefs[BOOKMARKS_KEY] = json.encodeToString(updated)
        }
    }

    private fun normalizeBookmark(bookmark: Bookmark): Bookmark? {
        val name = bookmark.name.trim()
        val path = bookmark.path.trim()
        if (name.isBlank() || path.isBlank()) return null
        val normalizedAccess = normalizeAccess(bookmark.access, path)
        val normalizedPath = normalizePath(path, normalizedAccess)
        return bookmark.copy(name = name, path = normalizedPath, access = normalizedAccess)
    }

    private fun normalizePath(path: String, access: String): String {
        if (access == "content") return path
        if (access == "file" && path.startsWith("file:")) {
            return Uri.parse(path).path?.trimEnd('/') ?: path
        }
        if ((access == "root" || access == "shizuku") && (path.startsWith("root:") || path.startsWith("shizuku:"))) {
            return Uri.parse(path).path?.trimEnd('/') ?: path
        }
        val trimmed = path.trim()
        if (trimmed == "/") return "/"
        return trimmed.trimEnd('/')
    }

    private fun normalizeAccess(access: String?, path: String): String {
        return when {
            access != null -> access
            path.startsWith("content://") -> "content"
            path.startsWith("file:") -> "file"
            path.startsWith("root:") -> "root"
            path.startsWith("shizuku:") -> "shizuku"
            else -> "file"
        }
    }
}
