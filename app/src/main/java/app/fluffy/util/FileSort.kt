package app.fluffy.util

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.fluffy.io.ShellEntry
import java.io.File

object FileSort {
    const val SORT_NAME = 0
    const val SORT_UPDATED = 1
    const val SORT_SIZE = 2
    const val SORT_ADDED = 3
    const val SORT_TYPE = 4

    private fun String.ext(): String {
        val name = this
        if (name.startsWith(".") && !name.drop(1).contains(".")) return ""
        return if (name.contains(".")) name.substringAfterLast(".").lowercase() else ""
    }

    private fun <T> applyReverse(list: List<T>, reverse: Boolean): List<T> =
        if (reverse) list.reversed() else list

    fun sortFiles(files: List<File>, sortMode: Int, reverse: Boolean): List<File> {
        val (dirs, regular) = files.partition { it.isDirectory }
        val sortedDirs = sortFileGroup(dirs, sortMode, reverse)
        val sortedFiles = sortFileGroup(regular, sortMode, reverse)
        return sortedDirs + sortedFiles
    }

    private fun sortFileGroup(group: List<File>, sortMode: Int, reverse: Boolean): List<File> {
        val sorted = when (sortMode) {
            SORT_TYPE -> group.sortedWith(compareBy<File> { it.name.ext() }.thenBy { it.name.lowercase() })
            SORT_SIZE -> group.sortedWith(compareBy<File> { it.length() }.thenBy { it.name.lowercase() })
            SORT_UPDATED, SORT_ADDED -> group.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name.lowercase() })
            else -> group.sortedWith(compareBy<File> { it.name.lowercase() })
        }
        return applyReverse(sorted, reverse)
    }

    fun sortDocuments(files: List<DocumentFile>, sortMode: Int, reverse: Boolean): List<DocumentFile> {
        val (dirs, regular) = files.partition { it.isDirectory }
        val sortedDirs = sortDocGroup(dirs, sortMode, reverse)
        val sortedFiles = sortDocGroup(regular, sortMode, reverse)
        return sortedDirs + sortedFiles
    }

    private fun sortDocGroup(group: List<DocumentFile>, sortMode: Int, reverse: Boolean): List<DocumentFile> {
        val sorted = when (sortMode) {
            SORT_TYPE -> group.sortedWith(compareBy<DocumentFile> { (it.name ?: "").ext() }.thenBy { (it.name ?: "").lowercase() })
            SORT_SIZE -> group.sortedWith(compareBy<DocumentFile> { it.length() }.thenBy { (it.name ?: "").lowercase() })
            SORT_UPDATED, SORT_ADDED -> group.sortedWith(compareBy<DocumentFile> { it.lastModified() }.thenBy { (it.name ?: "").lowercase() })
            else -> group.sortedWith(compareBy<DocumentFile> { (it.name ?: "").lowercase() })
        }
        return applyReverse(sorted, reverse)
    }

    fun sortShell(entries: List<ShellEntry>, sortMode: Int, reverse: Boolean): List<ShellEntry> {
        val (dirs, regular) = entries.partition { it.isDir }
        val sortedDirs = sortShellGroup(dirs, sortMode, reverse)
        val sortedFiles = sortShellGroup(regular, sortMode, reverse)
        return sortedDirs + sortedFiles
    }

    private fun sortShellGroup(group: List<ShellEntry>, sortMode: Int, reverse: Boolean): List<ShellEntry> {
        // Has no size/date metadata from list(), falls back to name/type only
        val sorted = when (sortMode) {
            SORT_TYPE -> group.sortedWith(compareBy<ShellEntry> { it.name.ext() }.thenBy { it.name.lowercase() })
            SORT_SIZE, SORT_UPDATED, SORT_ADDED -> {
                // No metadata: fall back to name but still respect reverse flag
                group.sortedWith(compareBy<ShellEntry> { it.name.lowercase() })
            }
            else -> group.sortedWith(compareBy<ShellEntry> { it.name.lowercase() })
        }
        return applyReverse(sorted, reverse)
    }
}
