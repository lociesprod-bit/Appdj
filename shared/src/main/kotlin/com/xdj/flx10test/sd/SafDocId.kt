package com.xdj.flx10test.sd

/**
 * Dựng documentId của SAF mà KHÔNG cần query.
 *
 * Với `com.android.externalstorage.documents` (thẻ SD / bộ nhớ trong), documentId có dạng
 * `<volume>:<đường dẫn tương đối trong volume>`, ví dụ tree `3661-3634:Music` →
 * file `3661-3634:Music/All Music DJ/11A - Ai Chung Tinh Duoc Mai HD.wav`.
 *
 * 0.15.0 tìm file bằng `SafTree.resolve()`: liệt kê từng thư mục trên đường dẫn và so tên. Với
 * `All Music DJ/` (khoảng 12.000 mục gồm bài, stems và file `._`) lần đầu mất ~11 s (`resolve=10984ms` trong LOG).
 * Dựng documentId trực tiếp tốn vài micro giây; xác minh bằng 1 query `stat` mất vài ms.
 *
 * Tên file phải lấy đúng như trên thẻ (từ lần liệt kê đã lưu trong cache), vì provider so tên theo byte:
 * tên tiếng Việt ghi từ macOS thường là NFD, khác NFC trong rekordbox XML.
 */
object SafDocId {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Provider này có documentId dạng đường dẫn không. */
    fun isPathShaped(authority: String?, treeDocumentId: String): Boolean =
        authority == EXTERNAL_STORAGE_AUTHORITY && treeDocumentId.indexOf(':') > 0

    /**
     * documentId của [relativePath] (ví dụ `All Music DJ/x.wav`) nằm dưới [treeDocumentId].
     * Trả null nếu treeDocumentId không có dạng `<volume>:<path>` hoặc đường dẫn có `.`/`..`.
     */
    fun child(treeDocumentId: String, relativePath: String): String? {
        val colon = treeDocumentId.indexOf(':')
        if (colon <= 0) return null
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
        val base = treeDocumentId.trimEnd('/')
        val sep = if (base.length == colon + 1) "" else "/"
        return base + sep + parts.joinToString("/")
    }

    /** Ngược lại: đường dẫn tương đối của [documentId] trong [treeDocumentId], hoặc null nếu nằm ngoài. */
    fun relative(treeDocumentId: String, documentId: String): String? {
        val base = treeDocumentId.trimEnd('/')
        if (documentId == base) return ""
        val colon = base.indexOf(':')
        if (colon <= 0) return null
        val prefix = if (base.length == colon + 1) base else "$base/"
        return if (documentId.startsWith(prefix)) documentId.substring(prefix.length) else null
    }

    /**
     * Đường dẫn File thật cho documentId của thẻ (dùng khi có "Quyền truy cập mọi tệp"):
     * `3661-3634:Music` → `/storage/3661-3634/Music`; `primary:Music` → `/storage/emulated/0/Music`.
     */
    fun filePath(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val path = documentId.substring(colon + 1).trim('/')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (path.isEmpty()) root else "$root/$path"
    }
}
