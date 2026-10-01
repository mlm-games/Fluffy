package app.fluffy.search

import android.net.Uri
import app.fluffy.io.ChildRef
import app.fluffy.io.SafIo

data class SearchHit(
    val name: String,
    val uri: Uri,
    val parentPath: String,
    val isDir: Boolean
)

data class SearchOutcome(
    val hits: List<SearchHit>,
    val truncated: Boolean,
    val visitedDirs: Int
)

class FileSearchEngine(private val io: SafIo) {

    fun search(
        root: Uri,
        query: String,
        showHidden: Boolean = false,
        shouldStop: () -> Boolean = { false },
        maxResults: Int = 2000,
        maxVisitedDirs: Int = 50_000
    ): Sequence<SearchOutcome> {
        val lowerQuery = query.trim().lowercase()
        if (lowerQuery.isEmpty()) return sequenceOf(SearchOutcome(emptyList(), false, 0))

        val walker = DirWalker(
            listChildren = { key ->
                io.listChildRefs(Uri.parse(key))
                    .filter { showHidden || !it.name.startsWith(".") }
                    .toNodes()
            },
            isSymlink = { key -> io.isSymlinkUri(Uri.parse(key)) }
        )

        val result = walker.find(
            rootKey = io.cycleKey(root),
            rootPath = pathOf(root),
            lowerQuery = lowerQuery,
            shouldStop = shouldStop,
            maxResults = maxResults,
            maxVisitedDirs = maxVisitedDirs
        )

        return sequenceOf(
            SearchOutcome(
                hits = result.hits.map { it.toHit() },
                truncated = result.truncated,
                visitedDirs = result.visitedDirs
            )
        )
    }

    private fun List<ChildRef>.toNodes(): List<WalkNode> = map { ref ->
        WalkNode(
            key = io.cycleKey(ref.uri),
            name = ref.name,
            isDir = ref.isDir,
            pathLabel = ""
        )
    }

    private fun WalkHit.toHit() = SearchHit(
        name = name,
        uri = Uri.parse(key),
        parentPath = parentPath,
        isDir = isDir
    )

    private fun pathOf(uri: Uri): String {
        val p = uri.path?.ifEmpty { "/" } ?: return "/"
        if (uri.scheme == "content") {
            val marker = "/document/"
            val doc = p.indexOf(marker)
            if (doc >= 0) return p.substring(doc + marker.length)
            val tree = "/tree/"
            val treeIdx = p.indexOf(tree)
            if (treeIdx >= 0) return p.substring(treeIdx + tree.length)
        }
        return p
    }
}
