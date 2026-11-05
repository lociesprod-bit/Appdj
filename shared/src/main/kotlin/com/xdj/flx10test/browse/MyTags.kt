package com.xdj.flx10test.browse

/**
 * My Tag của rekordbox nằm trong Comment khi bật "Thêm My Tag vào comment":
 * `/* Club / House Lak / Bass Music / Remix / Grooving Up / Viet Nam */`.
 * rekordbox XML không xuất My Tag riêng, nên lấy từ khối này.
 */
object MyTags {
    data class Split(val tags: List<String>, val rest: String)

    private val BLOCK = Regex("/\\*(.*?)\\*/", RegexOption.DOT_MATCHES_ALL)
    private val SEPARATOR = Regex("\\s+/\\s+")

    /** Tách mọi khối `/* … */` thành danh sách tag (giữ thứ tự, bỏ trùng); [Split.rest] là phần comment còn lại. */
    fun split(comment: String?): Split {
        if (comment.isNullOrBlank()) return Split(emptyList(), "")
        val tags = LinkedHashSet<String>()
        BLOCK.findAll(comment).forEach { m ->
            m.groupValues[1].split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags.add(it) }
        }
        if (tags.isEmpty()) return Split(emptyList(), comment.trim())
        val rest = BLOCK.replace(comment, " ").replace(Regex("\\s{2,}"), " ").trim()
        return Split(tags.toList(), rest)
    }

    /** Tag đã có (export.pdb) thì giữ; không có thì lấy từ comment, rồi tới genre. */
    fun resolve(existing: List<String>?, comment: String?, genre: String?): List<String> {
        if (!existing.isNullOrEmpty()) return existing
        val fromComment = split(comment).tags
        if (fromComment.isNotEmpty()) return fromComment
        return split(genre).tags
    }
}
