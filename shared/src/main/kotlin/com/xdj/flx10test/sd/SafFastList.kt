package com.xdj.flx10test.sd

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong

/**
 * Liệt kê SAF nhanh: mỗi thư mục 1 query với projection tối thiểu, đọc cursor một lượt.
 *
 * Khác `SafTree.children()` của 0.15.0 ở ba điểm:
 * 1. Không bọc từng `moveToNext()` trong `PerformanceResources.io` (12.000 lần admission cho `All Music DJ/`).
 * 2. Không giữ `synchronized(cache)` trong lúc query. Ở 0.15.0, một lần liệt kê 10 s khoá cả thread LOAD.
 * 3. Có [stat] (1 query trên document URI) để resolve/kiểm tra mtime mà không phải liệt kê thư mục cha.
 */
class SafFastList(
    val resolver: ContentResolver,
    val treeUri: Uri,
) : DirSource {
    val treeDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)
    val pathShaped: Boolean = SafDocId.isPathShaped(treeUri.authority, treeDocumentId)

    val queries = AtomicLong()
    val queryNanos = AtomicLong()

    fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun list(id: String): List<DirEntry> {
        val started = System.nanoTime()
        queries.incrementAndGet()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
            val cursor = resolver.query(uri, PROJECTION, null, null, null)
                ?: throw FileNotFoundException("Không đọc được thư mục $id")
            cursor.use { c ->
                val out = ArrayList<DirEntry>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    out.add(
                        DirEntry(
                            id = c.getString(0),
                            name = c.getString(1) ?: "",
                            directory = Document.MIME_TYPE_DIR == c.getString(2),
                            size = if (c.isNull(3)) 0L else c.getLong(3),
                            modified = if (c.isNull(4)) 0L else c.getLong(4),
                        ),
                    )
                }
                return out
            }
        } finally {
            queryNanos.addAndGet(System.nanoTime() - started)
        }
    }

    override fun stat(id: String): DirEntry? {
        val started = System.nanoTime()
        queries.incrementAndGet()
        return try {
            resolver.query(documentUri(id), PROJECTION, null, null, null)?.use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    DirEntry(
                        id = c.getString(0) ?: id,
                        name = c.getString(1) ?: "",
                        directory = Document.MIME_TYPE_DIR == c.getString(2),
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        modified = if (c.isNull(4)) 0L else c.getLong(4),
                    )
                }
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            queryNanos.addAndGet(System.nanoTime() - started)
        }
    }

    /**
     * Resolve nhanh [relativePath] (ví dụ `All Music DJ/x.wav`) thành URI: dựng documentId rồi `stat` 1 lần.
     * Trả null nếu provider không có documentId dạng đường dẫn, hoặc file không còn. Khi đó gọi cách cũ.
     */
    fun resolve(relativePath: String): Resolved? {
        if (!pathShaped) return null
        val id = SafDocId.child(treeDocumentId, relativePath) ?: return null
        val entry = stat(id) ?: return null
        return Resolved(documentUri(entry.id), entry)
    }

    data class Resolved(val uri: Uri, val entry: DirEntry)

    fun diagnostics(): String = "SAF: ${queries.get()} query, ${queryNanos.get() / 1_000_000} ms"

    companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/**
 * Liệt kê bằng java.io.File (cần "Quyền truy cập mọi tệp" trên Android 11+). id = đường dẫn tuyệt đối.
 * Dùng để Lab so tốc độ với SAF trên thẻ thật.
 */
class FileList : DirSource {
    override fun list(id: String): List<DirEntry> {
        val dir = File(id)
        val files = dir.listFiles() ?: throw FileNotFoundException("Không đọc được $id")
        return files.map { f ->
            val isDir = f.isDirectory
            DirEntry(f.absolutePath, f.name, isDir, if (isDir) 0L else f.length(), f.lastModified())
        }
    }

    override fun stat(id: String): DirEntry? {
        val f = File(id)
        if (!f.exists()) return null
        val isDir = f.isDirectory
        return DirEntry(f.absolutePath, f.name, isDir, if (isDir) 0L else f.length(), f.lastModified())
    }
}
