package app.fluffy.search

data class WalkNode(
    val key: String,
    val name: String,
    val isDir: Boolean,
    val pathLabel: String
)

data class WalkHit(
    val name: String,
    val key: String,
    val parentPath: String,
    val isDir: Boolean
)

data class WalkResult(
    val hits: List<WalkHit>,
    val truncated: Boolean,
    val visitedDirs: Int
)

class DirWalker(
    private val listChildren: (key: String) -> List<WalkNode>,
    private val isSymlink: (key: String) -> Boolean
) {
    fun find(
        rootKey: String,
        rootPath: String,
        lowerQuery: String,
        shouldStop: () -> Boolean = { false },
        maxResults: Int = 2000,
        maxVisitedDirs: Int = 50_000
    ): WalkResult {
        val needle = lowerQuery.trim().lowercase()
        if (needle.isEmpty()) return WalkResult(emptyList(), false, 0)

        val hits = ArrayList<WalkHit>()
        val hitKeys = HashSet<String>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<WalkNode>()
        queue.add(WalkNode(rootKey, "", true, rootPath))


        var visitedDirs = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (shouldStop()) return WalkResult(hits, truncated, visitedDirs)
            if (visitedDirs >= maxVisitedDirs) return WalkResult(hits, true, visitedDirs)

            val dir = queue.removeFirst()
            if (!visited.add(dir.key)) continue
            visitedDirs++

            val children = listChildren(dir.key)
            for (child in children) {
                if (hits.size >= maxResults) {
                    truncated = true
                    break
                }
                val matches = child.name.lowercase().contains(needle)
                if (child.isDir) {
                    if (isSymlink(child.key)) continue
                    queue.add(child.copy(pathLabel = joinPath(dir.pathLabel, child.name)))
                    if (matches && hitKeys.add(child.key)) {
                        hits.add(WalkHit(child.name, child.key, dir.pathLabel, isDir = true))
                    }
                } else if (matches && hitKeys.add(child.key)) {
                    hits.add(WalkHit(child.name, child.key, dir.pathLabel, isDir = false))
                }
            }

            if (truncated) return WalkResult(hits, true, visitedDirs)
        }

        return WalkResult(hits, false, visitedDirs)
    }
}

private fun joinPath(base: String, name: String): String =
    if (base.endsWith("/")) base + name else "$base/$name"
