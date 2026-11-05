package com.xdj.flx10test.lab

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.xdj.flx10test.sd.SafFastList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** LOG của Lab: giữ trong bộ nhớ và ghi đè file `Music/LOG/LAB_<thời gian>.log` sau mỗi thao tác. */
class LabLog(onLine: (String) -> Unit) {
    /** Màn hình đang hiện LOG (đổi khi chuyển giữa màn chính và màn Sóng & grid). */
    @Volatile var onLine: (String) -> Unit = onLine
    private val lines = ArrayList<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: Uri? = null
    val fileName: String = "LAB_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"

    @Synchronized
    fun line(text: String) {
        val first = time.format(Date()) + " " + text.lineSequence().first()
        val rest = text.lineSequence().drop(1).map { "    $it" }.toList()
        lines.add(first)
        lines.addAll(rest)
        onLine((listOf(first) + rest).joinToString("\n"))
    }

    @Synchronized
    fun snapshot(): List<String> = ArrayList(lines)

    /** Ghi toàn bộ LOG ra thẻ. Trả lỗi (chuỗi) nếu không ghi được. */
    fun flush(resolver: ContentResolver, list: SafFastList?): String? {
        if (list == null) return "chưa chọn thư mục Music"
        return try {
            val target = file ?: createFile(resolver, list).also { file = it }
            val text = snapshot().joinToString("\n", postfix = "\n")
            resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: return "không mở được file LOG"
            null
        } catch (e: Exception) {
            file = null
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun createFile(resolver: ContentResolver, list: SafFastList): Uri {
        val rootId = list.treeDocumentId
        val logDir = list.list(rootId).firstOrNull { it.directory && it.name == "LOG" }?.id
            ?: DocumentsContract.createDocument(resolver, list.documentUri(rootId), DocumentsContract.Document.MIME_TYPE_DIR, "LOG")
                ?.let { DocumentsContract.getDocumentId(it) }
            ?: error("không tạo được thư mục LOG")
        // octet-stream để provider giữ đuôi .log (với text/plain có thể bị thêm .txt).
        return DocumentsContract.createDocument(resolver, list.documentUri(logDir), "application/octet-stream", fileName)
            ?: error("không tạo được $fileName")
    }
}
