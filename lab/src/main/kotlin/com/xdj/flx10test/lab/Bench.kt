package com.xdj.flx10test.lab

import android.content.Context
import android.os.Build
import android.os.Environment
import com.xdj.flx10test.sd.CountingSink
import com.xdj.flx10test.sd.FileList
import com.xdj.flx10test.sd.ParallelStemDecoder
import com.xdj.flx10test.sd.ParallelWalker
import com.xdj.flx10test.sd.SafDocId
import com.xdj.flx10test.sd.SafFastList
import com.xdj.flx10test.sd.StemDecodeScheduler
import java.io.File

/** Các phép đo của Lab. Gọi từ luồng nền; kết quả ghi vào LOG. */
class Bench(private val ctx: Context, private val list: SafFastList, private val log: LabLog) {

    private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000.0

    /**
     * Resolve [sample] bài:
     * - cách mới: dựng documentId + 1 `stat`;
     * - cách cũ (0.15.0): liệt kê thư mục cha rồi so tên. Lần đầu sau khi mở app tốn ~11 s với `All Music DJ/`.
     */
    fun resolve(sample: List<LabTrack>) {
        if (sample.isEmpty()) {
            log.line("Đo resolve: chưa có bài")
            return
        }
        if (!list.pathShaped) log.line("⚠ Provider ${list.treeUri.authority} không có documentId dạng đường dẫn: chỉ đo cách cũ")
        val fast = ArrayList<Double>()
        var misses = 0
        for (t in sample) {
            val t0 = System.nanoTime()
            val r = list.resolve(t.relPath)
            fast.add(ms(t0))
            if (r == null || r.entry.size != t.size) misses++
        }
        val first = sample.first()
        val parentPath = first.relPath.substringBeforeLast('/', "")
        val parentId = SafDocId.child(list.treeDocumentId, parentPath) ?: list.treeDocumentId
        val t1 = System.nanoTime()
        val entries = list.list(parentId)
        val found = entries.firstOrNull { it.name == first.relPath.substringAfterLast('/') }
        val old = ms(t1)
        log.line(
            "Đo resolve ${sample.size} bài · mới: trung bình ${"%.1f".format(fast.average())} ms, chậm nhất ${"%.1f".format(fast.max())} ms" +
                (if (misses > 0) ", $misses bài không khớp" else "") +
                " · cũ (liệt kê '$parentPath', ${entries.size} mục): ${"%.0f".format(old)} ms" +
                (if (found == null) " (không thấy file!)" else ""),
        )
    }

    /** Tách 4 stem rồi giải mã lần lượt và song song 2/3/4 luồng; so với giải mã bài gốc. */
    fun stems(track: LabTrack) {
        val stemsId = track.stemsId
        if (stemsId == null) {
            log.line("Đo stems: bài '${track.row.title}' không có file stems")
            return
        }
        val folder = File(ctx.cacheDir, "lab-stems")
        folder.deleteRecursively()
        try {
            log.line("Đo stems: ${track.row.title} · ${track.stemsName}")
            val t0 = System.nanoTime()
            val extracted = list.resolver.openInputStream(list.documentUri(stemsId))?.use { SeratoStemsFile.extract(it, folder) }
                ?: error("Không mở được file stems")
            log.line(
                "  tách 4 stem (XOR → MP3 tạm): ${"%.0f".format(ms(t0))} ms · ${extracted.bytes / 1024} KB · " +
                    "${extracted.rate} Hz · ${extracted.samples / extracted.rate.coerceAtLeast(1)} s",
            )

            val t1 = System.nanoTime()
            val original = ParallelStemDecoder.decodeOne(
                ParallelStemDecoder.Job("gốc", { it.setDataSource(ctx, list.documentUri(track.documentId), null) }, CountingSink()),
            )
            log.line("  bài gốc: ${"%.0f".format(ms(t1))} ms · ${original.codec} · ${original.mime} · ${original.frames} frame${original.error?.let { " · lỗi $it" } ?: ""}")

            val cores = Runtime.getRuntime().availableProcessors()
            val plan = StemDecodeScheduler.plan(4, codecLimit = 3, codecsInUse = 0, cueReserve = 1, anyDeckPlaying = false, cpuCores = cores)
            for (parallel in listOf(1, 2, 3, 4)) {
                val jobs = extracted.stems.map { s ->
                    ParallelStemDecoder.Job(SeratoStemsFile.typeName(s.type), { it.setDataSource(s.file.absolutePath) }, CountingSink())
                }
                val t2 = System.nanoTime()
                val results = ParallelStemDecoder(parallel).decodeAll(jobs)
                val total = ms(t2)
                val each = results.joinToString(", ") { r -> "${r.label} ${r.millis} ms" + (r.error?.let { e -> " lỗi $e" } ?: "") }
                val mark = if (parallel == plan.parallel) "  ← app sẽ dùng (codecLimit 3, cueReserve 1, $cores nhân)" else ""
                log.line("  4 stem, $parallel luồng: ${"%.0f".format(total)} ms ($each)$mark")
            }
        } catch (e: Exception) {
            log.line("⚠ Đo stems lỗi: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            folder.deleteRecursively()
        }
    }

    /** "Quyền truy cập mọi tệp" chỉ có từ Android 11 (API 30). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** So liệt kê `All Music DJ/` qua SAF và qua java.io.File (cần "Quyền truy cập mọi tệp"). */
    fun safVsFile(musicDocumentId: String) {
        val path = SafDocId.filePath(musicDocumentId)
        if (path == null) {
            log.line("SAF vs File: không đổi được '$musicDocumentId' thành đường dẫn")
            return
        }
        val t0 = System.nanoTime()
        val (safDirs, safStats) = ParallelWalker(list, LabLibrary.THREADS).walk(musicDocumentId, LabLibrary.MUSIC)
        val saf = ms(t0)
        val t1 = System.nanoTime()
        val (fileDirs, fileStats) = try {
            ParallelWalker(FileList(), LabLibrary.THREADS).walk(path, LabLibrary.MUSIC)
        } catch (e: Exception) {
            log.line("SAF vs File: File lỗi (${e.message}); đã cấp 'Quyền truy cập mọi tệp' chưa?")
            return
        }
        val file = ms(t1)
        val t2 = System.nanoTime()
        val one = FileList().list(path).size
        val fileOne = ms(t2)
        log.line(
            "SAF vs File ($path): SAF ${"%.0f".format(saf)} ms (${safDirs.size} thư mục, ${safStats.entries} mục) · " +
                "File ${"%.0f".format(file)} ms (${fileDirs.size} thư mục, ${fileStats.entries} mục) · " +
                "File 1 thư mục gốc ($one mục) ${"%.0f".format(fileOne)} ms",
        )
    }
}
