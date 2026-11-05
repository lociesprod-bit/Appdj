package com.xdj.flx10test.sd

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Chọn cách đọc thẻ SD.
 *
 * - [Mode.FILE]: java.io.File trên `/storage/<volume>/…`. Cần "Quyền truy cập mọi tệp" (MANAGE_EXTERNAL_STORAGE,
 *   Android 11+); app cài ngoài nên xin được. Lab đo trên tablet: liệt kê cả `All Music DJ` mất 595 ms, SAF mất 7 438 ms.
 * - [Mode.SAF]: Storage Access Framework như 0.15.0. Dùng khi chưa cấp quyền, quyền bị thu hồi,
 *   hoặc provider không có documentId dạng đường dẫn.
 *
 * Mọi id trả về ([rootId], [childId], [DirEntry.id]) thuộc nguồn đang dùng: đường dẫn tuyệt đối với FILE,
 * documentId với SAF. Đường dẫn tương đối (`All Music DJ/x.mp3`) thì giống nhau ở cả hai chế độ.
 */
class SdAccess private constructor(
    val mode: Mode,
    val saf: SafFastList,
    /** Thư mục gốc dạng File (ví dụ `/storage/3661-3634/Music`), null ở chế độ SAF. */
    val fileRoot: String?,
) {
    enum class Mode { FILE, SAF }

    private val files = FileList()

    val source: DirSource get() = if (mode == Mode.FILE) files else saf

    /** id thư mục gốc (thư mục Music đã chọn) theo nguồn đang dùng. */
    val rootId: String get() = fileRoot ?: saf.treeDocumentId

    /** id của [relativePath] theo nguồn đang dùng, không cần query. Null nếu không dựng được (SAF provider khác). */
    fun childId(relativePath: String): String? = if (mode == Mode.FILE) {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) null else fileRoot + "/" + parts.joinToString("/")
    } else {
        SafDocId.child(saf.treeDocumentId, relativePath)
    }

    /**
     * File của một bài đã tìm thấy.
     * [uri] luôn có: content URI, dựng không cần query, cho code còn dùng URI.
     * [path] chỉ có ở chế độ FILE: dùng cho `MediaExtractor.setDataSource(path)`.
     */
    data class Resolved(val uri: Uri?, val path: String?, val entry: DirEntry)

    /** Resolve [relativePath]: FILE → 1 lần `stat` file; SAF → dựng documentId + 1 query. */
    fun resolve(relativePath: String): Resolved? {
        if (mode == Mode.SAF) return saf.resolve(relativePath)?.let { Resolved(it.uri, null, it.entry) }
        val path = childId(relativePath) ?: return null
        val entry = files.stat(path)?.takeIf { !it.directory } ?: return null
        val uri = SafDocId.child(saf.treeDocumentId, relativePath)?.let { saf.documentUri(it) }
        return Resolved(uri, path, entry)
    }

    /** Mở luồng đọc một file theo id của nguồn đang dùng. */
    fun openInputStream(id: String): InputStream = if (mode == Mode.FILE) {
        FileInputStream(id)
    } else {
        saf.resolver.openInputStream(saf.documentUri(id)) ?: throw FileNotFoundException(id)
    }

    /** Gán nguồn cho MediaExtractor theo id của nguồn đang dùng (FILE: đường dẫn; SAF: content URI). */
    fun setExtractorSource(extractor: MediaExtractor, context: Context, id: String) {
        if (mode == Mode.FILE) extractor.setDataSource(id) else extractor.setDataSource(context, saf.documentUri(id), null)
    }

    fun describe(): String = when (mode) {
        Mode.FILE -> "File ($fileRoot)"
        Mode.SAF -> "SAF (${saf.treeDocumentId})"
    }

    companion object {
        /** Chế độ FILE với thư mục gốc cho trước (dùng cho test). */
        internal fun fileMode(saf: SafFastList, root: String) = SdAccess(Mode.FILE, saf, root.trimEnd('/'))

        /** Đã có "Quyền truy cập mọi tệp" chưa (chỉ có từ Android 11). */
        fun hasAllFilesAccess(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

        /**
         * Chọn FILE nếu [allFilesAccess] và thư mục đọc được qua File; ngược lại SAF.
         * [preferSaf] = true để ép SAF (Lab dùng để so tốc độ).
         */
        fun choose(
            resolver: ContentResolver,
            treeUri: Uri,
            allFilesAccess: Boolean = hasAllFilesAccess(),
            preferSaf: Boolean = false,
        ): SdAccess {
            val saf = SafFastList(resolver, treeUri)
            if (!preferSaf && allFilesAccess && saf.pathShaped) {
                val path = SafDocId.filePath(saf.treeDocumentId)
                if (path != null && usableDirectory(File(path))) return SdAccess(Mode.FILE, saf, path)
            }
            return SdAccess(Mode.SAF, saf, null)
        }

        private fun usableDirectory(dir: File): Boolean = try {
            dir.isDirectory && dir.list() != null
        } catch (_: SecurityException) {
            false
        }
    }
}
