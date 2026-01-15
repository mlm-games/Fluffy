package app.fluffy.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.fluffy.io.ShellIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.bookmarksDataStore by preferencesDataStore(name = "bookmarks")

@Serializable
data class Bookmark(
    val name: String,
    val path: String,
    val isAutoDetected: Boolean = false
)

class BookmarksRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        private val BOOKMARKS_KEY = stringPreferencesKey("custom_bookmarks")

        /** Asked paths in issue */
        val SHELL_PATHS = listOf(
            Bookmark("Shell Data", "/data/data/com.android.shell"),
            Bookmark("Shell Data (alt)", "/data/user/0/com.android.shell"),
            Bookmark("Shell DE Storage", "/data/user_de/0/com.android.shell"),
            Bookmark("Local Tmp", "/data/local/tmp"),
        )
    }

    val bookmarks: Flow<List<Bookmark>> = context.bookmarksDataStore.data.map { prefs ->
        val stored = prefs[BOOKMARKS_KEY]?.let {
            runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrElse { emptyList() }
        } ?: emptyList()
        stored
    }

    suspend fun addBookmark(bookmark: Bookmark) {
        context.bookmarksDataStore.edit { prefs ->
            val current = prefs[BOOKMARKS_KEY]?.let {
                runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()

            // Avoid duplicates by path
            val updated = current.filterNot { it.path == bookmark.path } + bookmark.copy(isAutoDetected = false)
            prefs[BOOKMARKS_KEY] = json.encodeToString(updated)
        }
    }

    suspend fun removeBookmark(path: String) {
        context.bookmarksDataStore.edit { prefs ->
            val current = prefs[BOOKMARKS_KEY]?.let {
                runCatching { json.decodeFromString<List<Bookmark>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()

            val updated = current.filterNot { it.path == path }
            prefs[BOOKMARKS_KEY] = json.encodeToString(updated)
        }
    }

    fun detectExpandedStoragePaths(): List<Bookmark> {
        val bookmarks = mutableListOf<Bookmark>()
        try {
            val mountExpand = java.io.File("/mnt/expand")
            if (mountExpand.exists() && mountExpand.isDirectory) {
                mountExpand.listFiles()?.forEach { uuidDir ->
                    if (uuidDir.isDirectory && uuidDir.name.matches(Regex("[a-f0-9-]+"))) {
                        val tmpPath = "${uuidDir.absolutePath}/local/tmp"
                        val tmpDir = java.io.File(tmpPath)
                        // Check if accessible (may need shell)
                        if (tmpDir.exists() || tmpDir.canRead()) {
                            bookmarks.add(
                                Bookmark(
                                    name = "Expanded Tmp (${uuidDir.name.take(8)}…)",
                                    path = tmpPath,
                                    isAutoDetected = true
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return bookmarks
    }

    suspend fun detectExpandedStorageViaShell(useRoot: Boolean): List<Bookmark> = withContext(Dispatchers.IO) {
        val bookmarks = mutableListOf<Bookmark>()
        try {
            val uuids = if (useRoot) {
                ShellIo.listRoot("/mnt/expand").map { it.first }
            } else {
                ShellIo.listShizuku("/mnt/expand").map { it.first }
            }

            for (uuid in uuids) {
                if (!uuid.matches(Regex("[a-f0-9-]+"))) continue
                val tmpPath = "/mnt/expand/$uuid/local/tmp"

                // Verify path exists
                val children = if (useRoot) {
                    ShellIo.listRoot("/mnt/expand/$uuid/local")
                } else {
                    ShellIo.listShizuku("/mnt/expand/$uuid/local")
                }

                if (children.any { it.first == "tmp" && it.second }) {
                    bookmarks.add(
                        Bookmark(
                            name = "Expanded Tmp (${uuid.take(8)}…)",
                            path = tmpPath,
                            isAutoDetected = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        bookmarks
    }

    suspend fun verifyShellAccess(path: String, useRoot: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = if (useRoot) {
                ShellIo.listRoot(path)
            } else {
                ShellIo.listShizuku(path)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}