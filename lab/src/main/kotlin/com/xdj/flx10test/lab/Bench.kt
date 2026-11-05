package com.xdj.flx10test.lab

import android.content.Context
import android.media.MediaExtractor
import com.xdj.flx10test.sd.CountingSink
import com.xdj.flx10test.sd.FileList
import com.xdj.flx10test.sd.ParallelStemDecoder
import com.xdj.flx10test.sd.ParallelWalker
import com.xdj.flx10test.sd.SafDocId
import com.xdj.flx10test.sd.SdAccess
import com.xdj.flx10test.sd.StemDecodeScheduler
import java.io.File

/** Các phép đo của Lab. Gọi từ luồng nền; kết quả ghi vào LOG. */
class Bench(private val ctx: Context, private val access: SdAccess, private val log: LabLog) {
    private val saf get() = access.saf

    private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000.0
    private fun f1(v: Double) = "%.1f".format(v)
    private fun f0(v: Double) = "%.0f".format(v)

    /**
     * Resolve [sample] bài:
     * - cách mới theo nguồn đang dùng (File: 1 stat; SAF: dựng documentId + 1 query);
     * - cách cũ (0.15.0): liệt kê thư mục cha qua SAF rồi so tên. Lần đầu sau khi mở app tốn ~8–11 s với `All Music DJ/`.
     * Sau đó đo mở file cho MediaExtractor: content URI và đường dẫn File.
     * App 0.15.0 có `sequentialSourceSetup` ~340 ms qua content URI.
     */
    fun resolve(sample: List<LabTrack>, includeOld: Boolean) {
        if (sample.isEmpty()) {
            log.line("Đo resolve: chưa có bài")
            return
        }
        val fast = ArrayList<Double>()
        var misses = 0
        for (t in sample) {
            val t0 = System.nanoTime()
            val r = access.resolve(t.relPath)
            fast.add(ms(t0))
            if (r == null || r.entry.size != t.size) misses++
        }
        var line = "Đo resolve ${sample.size} bài · ${access.mode}: trung bình ${f1(fast.average())} ms, chậm nhất ${f1(fast.max())} ms" +
            if (misses > 0) ", $misses bài không khớp" else ""
        if (access.mode == SdAccess.Mode.FILE && saf.pathShaped) {
            val safTimes = sample.map { t ->
                val t0 = System.nanoTime()
                saf.resolve(t.relPath)
                ms(t0)
            }
            line += " · SAF dựng documentId: trung bình ${f1(safTimes.average())} ms"
        }
        if (includeOld) {
            val first = sample.first()
            val parentPath = first.relPath.substringBeforeLast('/', "")
            val parentId = SafDocId.child(saf.treeDocumentId, parentPath) ?: saf.treeDocumentId
            val t1 = System.nanoTime()
            val entries = saf.list(parentId)
            val found = entries.firstOrNull { it.name == first.relPath.substringAfterLast('/') }
            line += " · cũ 0.15.0 (liệt kê SAF '$parentPath', ${entries.size} mục): ${f0(ms(t1))} ms" +
                if (found == null) " (không thấy file!)" else ""
        }
        log.line(line)
        openFiles(sample.take(5))
    }

    /** Thời gian `MediaExtractor.setDataSource` + đọc format: content URI so với đường dẫn File. */
    private fun openFiles(sample: List<LabTrack>) {
        val viaUri = ArrayList<Double>()
        val viaPath = ArrayList<Double>()
        val fileRoot = access.fileRoot
        for (t in sample) {
            SafDocId.child(saf.treeDocumentId, t.relPath)?.let { id ->
                viaUri.add(timeExtractor { it.setDataSource(ctx, saf.documentUri(id), null) })
            }
            if (fileRoot != null) viaPath.add(timeExtractor { it.setDataSource("$fileRoot/${t.relPath}") })
        }
        val uri = if (viaUri.isEmpty()) "—" else "TB ${f0(viaUri.average())} ms (chậm nhất ${f0(viaUri.max())})"
        val path = if (viaPath.isEmpty()) "chưa có quyền truy cập mọi tệp" else "TB ${f0(viaPath.average())} ms (chậm nhất ${f0(viaPath.max())})"
        log.line("Mở file cho MediaExtractor ${sample.size} bài · content URI: $uri · đường dẫn File: $path")
    }

    private fun timeExtractor(open: (MediaExtractor) -> Unit): Double {
        val ex = MediaExtractor()
        val t0 = System.nanoTime()
        return try {
            open(ex)
            if (ex.trackCount > 0) ex.getTrackFormat(0)
            ms(t0)
        } catch (e: Exception) {
            log.line("  mở file lỗi: ${e.message ?: e.javaClass.simpleName}")
            ms(t0)
        } finally {
            ex.release()
        }
    }

    /** Tách 4 stem rồi giải mã với 1/2/4 luồng; so với giải mã bài gốc. */
    fun stems(track: LabTrack) {
        val stemsId = track.stemsId
        if (stemsId == null) {
            log.line("Đo stems: bài '${track.row.title}' không có file stems")
            return
        }
        val folder = File(ctx.cacheDir, "lab-stems")
        folder.deleteRecursively()
        try {
            log.line("Đo stems (${access.mode}): ${track.row.title} · ${track.stemsName}")
            val t0 = System.nanoTime()
            val extracted = access.openInputStream(stemsId).use { SeratoStemsFile.extract(it, folder) }
            log.line(
                "  tách 4 stem (XOR → MP3 tạm): ${f0(ms(t0))} ms · ${extracted.bytes / 1024} KB · " +
                    "${extracted.rate} Hz · ${extracted.samples / extracted.rate.coerceAtLeast(1)} s",
            )

            val t1 = System.nanoTime()
            val original = ParallelStemDecoder.decodeOne(
                ParallelStemDecoder.Job("gốc", { access.setExtractorSource(it, ctx, track.documentId) }, CountingSink()),
            )
            log.line("  bài gốc: ${f0(ms(t1))} ms · ${original.codec} · ${original.mime} · ${original.frames} frame${original.error?.let { " · lỗi $it" } ?: ""}")

            val cores = Runtime.getRuntime().availableProcessors()
            val idle = StemDecodeScheduler.plan(4, anyDeckPlaying = false, cpuCores = cores)
            val playing = StemDecodeScheduler.plan(4, anyDeckPlaying = true, cpuCores = cores)
            for (parallel in listOf(1, 2, 4)) {
                val jobs = extracted.stems.map { s ->
                    ParallelStemDecoder.Job(SeratoStemsFile.typeName(s.type), { it.setDataSource(s.file.absolutePath) }, CountingSink())
                }
                val t2 = System.nanoTime()
                val results = ParallelStemDecoder(parallel).decodeAll(jobs)
                val total = ms(t2)
                val each = results.joinToString(", ") { r -> "${r.label} ${r.millis} ms" + (r.error?.let { e -> " lỗi $e" } ?: "") }
                val marks = listOfNotNull(
                    "khi không deck nào phát".takeIf { parallel == idle.parallel },
                    "khi đang phát".takeIf { parallel == playing.parallel },
                )
                val mark = if (marks.isEmpty()) "" else "  ← app dùng " + marks.joinToString(" và ") + " ($cores nhân)"
                log.line("  4 stem, $parallel luồng: ${f0(total)} ms ($each)$mark")
            }
        } catch (e: Exception) {
            log.line("⚠ Đo stems lỗi: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            folder.deleteRecursively()
        }
    }

    /** So liệt kê `All Music DJ/` qua SAF và qua java.io.File (cần "Quyền truy cập mọi tệp"). */
    fun safVsFile() {
        val musicDocId = SafDocId.child(saf.treeDocumentId, LabLibrary.MUSIC)
        val path = musicDocId?.let { SafDocId.filePath(it) }
        if (musicDocId == null || path == null) {
            log.line("SAF vs File: provider không có documentId dạng đường dẫn")
            return
        }
        val t0 = System.nanoTime()
        val (safDirs, safStats) = ParallelWalker(saf, LabLibrary.THREADS).walk(musicDocId, LabLibrary.MUSIC)
        val safMs = ms(t0)
        val t1 = System.nanoTime()
        val (fileDirs, fileStats) = try {
            ParallelWalker(FileList(), LabLibrary.THREADS).walk(path, LabLibrary.MUSIC)
        } catch (e: Exception) {
            log.line("SAF vs File: File lỗi (${e.message}); đã cấp 'Quyền truy cập mọi tệp' chưa?")
            return
        }
        val fileMs = ms(t1)
        log.line(
            "SAF vs File ($path): SAF ${f0(safMs)} ms (${safDirs.size} thư mục, ${safStats.entries} mục) · " +
                "File ${f0(fileMs)} ms (${fileDirs.size} thư mục, ${fileStats.entries} mục)",
        )
    }
}
