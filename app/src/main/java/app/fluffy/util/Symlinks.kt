package app.fluffy.util

import java.io.File

fun File.isSymlink(): Boolean = try {
    android.system.OsConstants.S_ISLNK(android.system.Os.lstat(absolutePath).st_mode)
} catch (_: Exception) {
    false
}
