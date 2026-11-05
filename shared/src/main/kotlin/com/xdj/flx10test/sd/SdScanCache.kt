package com.xdj.flx10test.sd

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

data class CachedFile(val id: String, val name: String, val size: Long, val modified: Long)

data class CachedDir(
    val id: String,
    val path: String,
    val modified: Long,
    val files: List<CachedFile>,
    val subdirIds: List<String>,
)

/** Kết quả quét thẻ đã lưu: danh sách file từng thư mục + mtime thư mục. */
data class ScanSnapshot(
    val rootId: String,
    val rootPath: String,
    val dirs: List<CachedDir>,
    /** Dữ liệu kèm theo (ví dụ stamp file XML: "tên\tsize\tmtime"). */
    val extra: Map<String, String> = emptyMap(),
) {
    val fileCount: Int get() = dirs.sumOf { it.files.size }

    fun allFiles(): Sequence<Pair<CachedDir, CachedFile>> = dirs.asSequence().flatMap { d -> d.files.asSequence().map { d to it } }

    companion object {
        fun fromListings(rootId: String, rootPath: String, listings: List<DirListing>, extra: Map<String, String> = emptyMap()) =
            ScanSnapshot(rootId, rootPath, listings.map { it.toCached() }, extra)
    }
}

fun DirListing.toCached(): CachedDir = CachedDir(
    id = id,
    path = path,
    modified = modified,
    files = entries.filter { !it.directory }.map { CachedFile(it.id, it.name, it.size, it.modified) },
    subdirIds = entries.filter { it.directory }.map { it.id },
)

/**
 * Bộ nhớ đệm quét thẻ SD.
 *
 * Khi mở app: [load] (vài chục ms) → hiện danh sách ngay → [refresh] chạy nền: `stat` từng thư mục đã lưu
 * (1 query/thư mục) và chỉ liệt kê lại thư mục có mtime đổi.
 * 0.15.0 cũng dựa vào mtime thư mục: `SdLibrary.stamp()` dùng mtime của `All Music DJ`.
 */
object SdScanCache {
    private const val MAGIC = 0x464C5853 // "FLXS"
    private const val VERSION = 1

    data class Diff(val changed: Set<String>, val removed: Set<String>, val unchanged: Set<String>)

    /** So mtime đã lưu với mtime hiện tại (null = thư mục không còn). */
    fun diff(cached: Map<String, Long>, current: Map<String, Long?>): Diff {
        val changed = HashSet<String>()
        val removed = HashSet<String>()
        val unchanged = HashSet<String>()
        for ((id, old) in cached) {
            val now = current[id]
            when {
                !current.containsKey(id) -> changed.add(id)
                now == null -> removed.add(id)
                now != old || now <= 0L -> changed.add(id)
                else -> unchanged.add(id)
            }
        }
        return Diff(changed, removed, unchanged)
    }

    data class RefreshResult(
        val snapshot: ScanSnapshot,
        val checked: Int,
        val relisted: Int,
        val added: Int,
        val removed: Int,
        val millis: Long,
    ) {
        val unchanged: Boolean get() = relisted == 0 && added == 0 && removed == 0
    }

    /**
     * Cập nhật [cached] theo thẻ hiện tại qua [source]. Thư mục mới xuất hiện được quét đầy đủ bằng [ParallelWalker].
     */
    fun refresh(cached: ScanSnapshot, source: DirSource, threads: Int = 3): RefreshResult {
        val started = System.nanoTime()
        val byId = LinkedHashMap<String, CachedDir>()
        cached.dirs.forEach { byId[it.id] = it }
        val current = HashMap<String, Long?>()
        for (d in cached.dirs) current[d.id] = source.stat(d.id)?.modified
        val diff = diff(cached.dirs.associate { it.id to it.modified }, current)

        var relisted = 0
        var added = 0
        val gone = HashSet<String>(diff.removed)
        val walker = ParallelWalker(source, threads)
        for (id in diff.changed) {
            val old = byId[id] ?: continue
            val entries = try {
                source.list(id).filterNot { Junk.isJunk(it.name) }
            } catch (e: IOException) {
                gone.add(id)
                continue
            }
            relisted++
            val listing = DirListing(id, old.path, current[id] ?: 0L, entries)
            byId[id] = listing.toCached()
            val known = old.subdirIds.toSet()
            for (sub in entries.filter { it.directory }) {
                if (sub.id in known && byId.containsKey(sub.id)) continue
                val subPath = if (old.path.isEmpty()) sub.name else old.path + "/" + sub.name
                val (listings, _) = walker.walk(sub.id, subPath, sub.modified)
                listings.forEach { byId[it.id] = it.toCached() }
                added += listings.size
            }
            val nowSubs = entries.filter { it.directory }.map { it.id }.toSet()
            old.subdirIds.filter { it !in nowSubs }.forEach { gone.add(it) }
        }
        // Bỏ thư mục đã mất cùng toàn bộ thư mục con của nó.
        var removedCount = 0
        if (gone.isNotEmpty()) {
            val prefixes = gone.mapNotNull { byId[it]?.path }
            val drop = byId.values.filter { d ->
                d.id in gone || prefixes.any { p -> p.isEmpty() || d.path == p || d.path.startsWith("$p/") }
            }
            drop.forEach { byId.remove(it.id) }
            removedCount = drop.size
        }
        val snapshot = cached.copy(dirs = byId.values.sortedBy { it.path.lowercase() })
        return RefreshResult(snapshot, cached.dirs.size, relisted, added, removedCount, (System.nanoTime() - started) / 1_000_000)
    }

    fun save(file: File, snapshot: ScanSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(snapshot.rootId)
            out.writeUTF(snapshot.rootPath)
            out.writeInt(snapshot.extra.size)
            snapshot.extra.forEach { (k, v) ->
                out.writeUTF(k)
                writeLong(out, v)
            }
            out.writeInt(snapshot.dirs.size)
            for (d in snapshot.dirs) {
                out.writeUTF(d.id)
                out.writeUTF(d.path)
                out.writeLong(d.modified)
                out.writeInt(d.subdirIds.size)
                d.subdirIds.forEach { out.writeUTF(it) }
                out.writeInt(d.files.size)
                for (f in d.files) {
                    // id = id thư mục + "/" + tên với provider dạng đường dẫn; vẫn lưu đủ để không phụ thuộc giả định.
                    out.writeUTF(f.id)
                    out.writeUTF(f.name)
                    out.writeLong(f.size)
                    out.writeLong(f.modified)
                }
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Không ghi được cache ${file.name}")
        }
    }

    /** Đọc cache; null nếu không có, sai định dạng hoặc khác [rootId]. */
    fun load(file: File, rootId: String): ScanSnapshot? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val root = input.readUTF()
                if (root != rootId) return null
                val rootPath = input.readUTF()
                val extra = LinkedHashMap<String, String>()
                repeat(input.readInt()) { extra[input.readUTF()] = readLong(input) }
                val dirCount = input.readInt()
                require(dirCount in 0..1_000_000)
                val dirs = ArrayList<CachedDir>(dirCount)
                repeat(dirCount) {
                    val id = input.readUTF()
                    val path = input.readUTF()
                    val modified = input.readLong()
                    val subs = List(input.readInt().also { require(it in 0..1_000_000) }) { input.readUTF() }
                    val fileCount = input.readInt()
                    require(fileCount in 0..1_000_000)
                    val files = ArrayList<CachedFile>(fileCount)
                    repeat(fileCount) {
                        files.add(CachedFile(input.readUTF(), input.readUTF(), input.readLong(), input.readLong()))
                    }
                    dirs.add(CachedDir(id, path, modified, files, subs))
                }
                ScanSnapshot(root, rootPath, dirs, extra)
            }
        } catch (_: Exception) {
            null
        }
    }

    // writeUTF giới hạn 65 535 byte; giá trị extra có thể dài (ví dụ metadata XML đã tách) nên ghi dạng byte[].
    private fun writeLong(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readLong(input: DataInputStream): String {
        val n = input.readInt()
        require(n in 0..(256 shl 20))
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
