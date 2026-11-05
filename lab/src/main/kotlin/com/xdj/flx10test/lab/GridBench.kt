package com.xdj.flx10test.lab

import android.content.Context
import com.xdj.flx10test.sd.SdAccess
import com.xdj.flx10test.wave.BeatGrid
import com.xdj.flx10test.wave.GridOffsetEstimator
import java.util.Locale

/** Dùng chung giữa màn chính và màn Sóng & grid (cùng tiến trình). */
object LabSession {
    @Volatile var access: SdAccess? = null
    @Volatile var library: LabLibrary? = null
    @Volatile var log: LabLog? = null
}

/**
 * Đo grid rekordbox XML lệch bao nhiêu so với audio giải mã trên máy, cho nhiều bài thuộc nhiều định dạng.
 * Mục đích: tách lệch cố định do bộ giải mã (theo định dạng) khỏi độ trôi do BPM làm tròn.
 */
class GridBench(private val ctx: Context, private val access: SdAccess, private val library: LabLibrary, private val log: LabLog) {

    data class Row(val track: LabTrack, val ext: String, val result: GridOffsetEstimator.Result?, val fit: BeatGrid.ConstantFit?, val anchors: Int, val format: String)

    /** Lấy tối đa [perExt] bài mỗi định dạng (có grid trong XML), tổng tối đa [total]. */
    fun pick(perExt: Int = 5, total: Int = 20): List<LabTrack> {
        val withGrid = library.tracks.filter { it.row.bpm > 0 }
        val byExt = withGrid.groupBy { it.relPath.substringAfterLast('.', "").lowercase(Locale.ROOT) }
        val order = listOf("mp3", "wav", "m4a", "aac", "flac", "aiff", "aif", "ogg", "opus", "alac")
        val out = ArrayList<LabTrack>()
        for (ext in order) {
            val list = byExt[ext] ?: continue
            val step = (list.size / perExt).coerceAtLeast(1)
            out.addAll(list.filterIndexed { i, _ -> i % step == 0 }.take(perExt))
        }
        return out.take(total)
    }

    fun grids(tracks: List<LabTrack>): Map<String, RekordboxXmlLite.TrackGrid> {
        val (name, input) = library.openNewestXml() ?: return emptyMap()
        val keys = tracks.map { RekordboxXmlLite.matchKey(it.relPath.substringAfterLast('/')) }.toSet()
        val t0 = System.nanoTime()
        val map = input.use { RekordboxXmlLite.grids(it, keys) }
        log.line("XML $name: đọc TEMPO của ${map.size}/${keys.size} bài trong ${(System.nanoTime() - t0) / 1_000_000} ms")
        return map
    }

    fun run(tracks: List<LabTrack>, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): List<Row> {
        val grids = grids(tracks)
        val rows = ArrayList<Row>()
        for ((i, t) in tracks.withIndex()) {
            if (cancelled()) break
            progress(i + 1, tracks.size)
            val ext = t.relPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val xml = grids[RekordboxXmlLite.matchKey(t.relPath.substringAfterLast('/'))]
            try {
                val d = LabDecoder.decode(ctx, access, t, buildDetail = false, cancelled = cancelled)
                val grid = xml?.let { BeatGrid.fromTempos(it.tempos, d.durationSec) }
                val r = grid?.let { GridOffsetEstimator.estimate(d.mono, d.count, d.sampleRate, it) }
                val row = Row(t, ext, r, grid?.fitConstant(), xml?.tempos?.size ?: 0, d.describeFormat())
                rows.add(row)
                log.line(
                    "  [$ext] ${t.row.title.take(48)} · ${row.format} · ${row.anchors} mốc TEMPO · " +
                        (r?.describe() ?: "không đo được (ít kick?)") +
                        (row.fit?.let { " · BPM khớp %.3f (lệch tối đa %.1f ms)".format(it.bpm, it.maxErrorMs) } ?: ""),
                )
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log.line("  [$ext] ${t.row.title.take(48)} · lỗi ${e.message ?: e.javaClass.simpleName}")
            }
        }
        summary(rows)
        return rows
    }

    private fun summary(rows: List<Row>) {
        val ok = rows.filter { it.result != null }
        if (ok.isEmpty()) {
            log.line("Đo lệch grid: không bài nào đo được")
            return
        }
        log.line("Tổng kết lệch grid XML so với audio Android (dương = audio muộn hơn grid):")
        ok.groupBy { it.ext }.forEach { (ext, list) ->
            val med = list.map { it.result!!.medianMs }.sorted()
            val drift = list.map { it.result!!.driftMsPerMin }.sorted()
            log.line(
                "  %s: %d bài · lệch trung vị %+.1f ms (từ %+.1f tới %+.1f) · trôi trung vị %+.2f ms/phút".format(
                    ext.uppercase(Locale.ROOT), list.size, med[med.size / 2], med.first(), med.last(), drift[drift.size / 2],
                ),
            )
        }
    }
}
