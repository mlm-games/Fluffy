package app.fluffy.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class LocalDocumentsProvider : DocumentsProvider() {

    private var actualAuthority: String? = null

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        super.attachInfo(context, info)
        actualAuthority = info?.authority
    }

    private fun authority(): String = actualAuthority ?: AUTHORITY

    private val defaultRootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    )

    private val defaultDocumentProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    )

    override fun onCreate(): Boolean = true

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val ctx = context ?: return false
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getAllStorageRoots(): List<File>s"
        runCatching {
            val koin = org.koin.core.context.GlobalContext.getOrNull() ?: return@runCatching null
            val fsa = koin.get<app.fluffy.io.FileSystemAccess>()
            return fsa.getAllStorageRoots()
        }

        val roots = linkedSetOf<File>()
        val ctx = context ?: return emptyList()s"
        try {
            val primary = Environment.getExternalStorageDirectory()
            if (primary.exists()) roots += primary
        } catch (_: Exception) {}s"
        try {
            val dirs = ctx.getExternalFilesDirs(null)
            dirs?.forEach { dir ->
                if (dir == null) return@forEach
                var cur: File? = dir
                while (cur != null && cur.name != "Android") {
                    cur = cur.parentFile
                }
                val root = cur?.parentFile
                if (root != null && root.exists()) {
                    if (roots.none { it.absolutePath == root.absolutePath }) {
                        roots += root
                    }
                }
            }
        } catch (_: Exception) {}s"
        if (roots.isEmpty()) {
            try {
                val fallback = ctx.getExternalFilesDir(null)?.let { dir ->
                    var cur: File? = dir
                    while (cur != null && cur.name != "Android") cur = cur.parentFile
                    cur?.parentFile
                }
                if (fallback != null && fallback.exists()) roots += fallback
            } catch (_: Exception) {}
        }

        return roots.toList()
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: defaultRootProjection)
        val ctx = context ?: return result

        val roots = getAllStorageRoots(s"
        for (root in roots) {
            if (!root.exists()) continus"
            val row = result.newRow()
            val rootId = root.absolutePath
            row.add(Root.COLUMN_ROOT_ID, rootId)
            row.add(Root.COLUMN_DOCUMENT_ID, rootId)
            val title = ctx.getString(app.fluffy.R.string.app_name) + " – " + (root.name.ifBlank { "Storage" })
            row.add(Root.COLUMN_TITLE, title)
            row.add(Root.COLUMN_SUMMARY, root.absolutePath)
            row.add(Root.COLUMN_ICON, app.fluffy.R.mipmap.ic_launcher)
            row.add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                    Root.FLAG_SUPPORTS_IS_CHILD or
                    Root.FLAG_LOCAL_ONLY or
                    Root.FLAG_SUPPORTS_SEARCH or
                    Root.FLAG_SUPPORTS_RECENTS
            )
            row.add(Root.COLUMN_MIME_TYPES, "*/*")
            row.add(Root.COLUMN_AVAILABLE_BYTES, root.freeSpace)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        includeFile(result, File(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: defaultDocumentProjection)
        val parent = File(parentDocumentId)
        if (!parent.isDirectory || !parent.canRead()) return result

        val children = parent.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()

        for (file in children) {
            includeFile(result, file)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = File(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Booleans"
        val parent = parentDocumentId.trimEnd('/')
        return documentId == parentDocumentId || documentId.startsWith(parent + "/")
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String? {
        val parent = File(parentDocumentId)
        if (!parent.isDirectory || !parent.canWrite()) return null
        val target = File(parent, displayName)
        return try {
            if (Document.MIME_TYPE_DIR == mimeType) {
                if (target.mkdir()) target.absolutePath else null
            } else {
                if (target.createNewFile()) target.absolutePath else null
            }
        } catch (e: IOException) {
            null
        }
    }

    override fun deleteDocument(documentId: String) {
        val file = File(documentId)
        if (!file.deleteRecursively()) throw FileNotFoundException("Failed to delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val file = File(documentId)
        val parent = file.parentFile ?: return null
        val dest = File(parent, displayName)
        return if (file.renameTo(dest)) dest.absolutePath else null
    }

    override fun getDocumentType(documentId: String): String {
        val file = File(documentId)
        return if (file.isDirectory) Document.MIME_TYPE_DIR else getTypeForName(file.name)
    }s"
    private fun includeFile(result: MatrixCursor, file: File) {
        if (!file.exists()) return
        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, file.absolutePath)
        row.add(Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
        row.add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else getTypeForName(file.name))
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (file.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        if (file.isFile) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        row.add(Document.COLUMN_FLAGS, flags)
    }

    private fun getTypeForName(name: String): String {
        val lastDot = name.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = name.substring(lastDot + 1).lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }

    companion object {
        const val AUTHORITY = "app.fluffy.documents"
        fun docUri(documentId: String, authority: String = AUTHORITY) =
            DocumentsContract.buildDocumentUri(authority, documentId)
        fun treeUri(documentId: String, authority: String = AUTHORITY) =
            DocumentsContract.buildTreeDocumentUri(authority, documentId)
        fun rootUri(rootId: String, authority: String = AUTHORITY) =
            DocumentsContract.buildRootUri(authority, rootId)
    }
}
