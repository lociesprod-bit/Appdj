package com.xdj.flx10test.sd

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Một mục trong thư mục (giống `SafTree.Entry` của app 0.15.0). */
data class DirEntry(
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
)

/** Một thư mục đã liệt kê: [path] tương đối từ gốc quét (ví dụ `All Music DJ/Afro`). */
data class DirListing(
    val id: String,
    val path: String,
    val modified: Long,
    val entries: List<DirEntry>,
)

/** Nguồn liệt kê thư mục: SAF ([SafFastList]) hoặc java.io.File ([FileList]). */
interface DirSource {
    /** Liệt kê con trực tiếp của thư mục [id]. Lỗi đọc → ném exception. */
    fun list(id: String): List<DirEntry>

    /** Thông tin của chính mục [id] (để so mtime thư mục), null nếu không còn. */
    fun stat(id: String): DirEntry?
}

object Junk {
    /** File rác macOS và file ẩn: `._…`, `.DS_Store`, `.Spotlight-V100`… */
    fun isJunk(name: String): Boolean = name.startsWith(".")
}

/**
 * Duyệt cây thư mục, liệt kê nhiều thư mục con cùng lúc (mặc định 3 luồng).
 * Mỗi thư mục chỉ gọi [DirSource.list] đúng 1 lần.
 */
class ParallelWalker(
    private val source: DirSource,
    private val threads: Int = 3,
    private val skip: (DirEntry) -> Boolean = { Junk.isJunk(it.name) },
) {
    data class Stats(val dirs: Int, val entries: Int, val millis: Long)

    fun walk(
        rootId: String,
        rootPath: String,
        rootModified: Long = 0L,
        onDir: ((DirListing) -> Unit)? = null,
    ): Pair<List<DirListing>, Stats> {
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8)) { r ->
            Thread(r, "sd-walk").apply { isDaemon = true }
        }
        val done = ExecutorCompletionService<DirListing>(pool)
        val out = ArrayList<DirListing>()
        var pending = 0
        var entries = 0
        fun submit(id: String, path: String, modified: Long) {
            done.submit {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                DirListing(id, path, modified, source.list(id).filterNot(skip))
            }
            pending++
        }
        try {
            submit(rootId, rootPath, rootModified)
            while (pending > 0) {
                val future: Future<DirListing> = done.take()
                pending--
                val dir = future.get()
                out.add(dir)
                entries += dir.entries.size
                onDir?.invoke(dir)
                dir.entries.filter { it.directory }.forEach { child ->
                    submit(child.id, if (dir.path.isEmpty()) child.name else dir.path + "/" + child.name, child.modified)
                }
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
        out.sortBy { it.path.lowercase() }
        return out to Stats(out.size, entries, (System.nanoTime() - started) / 1_000_000)
    }
}
