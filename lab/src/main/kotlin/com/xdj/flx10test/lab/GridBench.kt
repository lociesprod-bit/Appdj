package com.xdj.flx10test.lab

import android.content.Context
import com.xdj.flx10test.sd.SdAccess
import com.xdj.flx10test.wave.BeatGrid
import com.xdj.flx10test.wave.GridOffsetEstimator
import com.xdj.flx10test.wave.GridOffsets
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
 *
 * Bản 2 (lab4):
 * - tách MP3 có LAME / không LAME (đọc trước header);
 * - tổng kết so với mốc lossless (WAV/FLAC);
 * - tính phần còn lại sau khi áp [GridOffsets].
 */
class GridBench(private val ctx: Context, private val access: SdAccess, private val library: LabLibrary, private val log: LabLog) {

    data class Row(
        val track: LabTrack,
        val kind: GridOffsets.Kind,
        val result: GridOffsetEstimator.Result?,
        val fit: BeatGrid.ConstantFit?,
        val anchors: Int,
        val format: String,
        val sampleRate: Int,
    )

    private fun ext(t: LabTrack) = t.relPath.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun spread(list: List<LabTrack>, n: Int): List<LabTrack> {
        if (list.size <= n) return list
        val step = list.size.toDouble() / n
        return (0 until n).map { list[(it * step).toInt()] }
    }

    /** 5 MP3 có LAME + 5 MP3 không LAME + tối đa 5 M4A + 3 WAV + 2 FLAC (có grid trong XML). */
    fun pick(): List<LabTrack> {
        val withGrid = library.tracks.filter { it.row.bpm > 0 }
        val byExt = withGrid.groupBy { ext(it) }
        val mp3 = spread(byExt["mp3"].orEmpty(), 300)
        val lame = ArrayList<LabTrack>()
        val plain = ArrayList<LabTrack>()
        val t0 = System.nanoTime()
        for (t in mp3) {
            if (lame.size >= 5 && plain.size >= 5) break
            val info = LabDecoder.mp3Info(access, t)
            if (info?.hasLame == true) {
                if (lame.size < 5) lame.add(t)
            } else if (plain.size < 5) {
                plain.add(t)
            }
        }
        log.line("Đo lệch grid: đọc header MP3 ${(System.nanoTime() - t0) / 1_000_000} ms · tìm được ${lame.size} MP3 có LAME, ${plain.size} không LAME")
        val m4a = spread(byExt["m4a"].orEmpty() + byExt["aac"].orEmpty(), 5)
        val wav = spread(byExt["wav"].orEmpty(), 3)
        val flac = spread(byExt["flac"].orEmpty() + byExt["aiff"].orEmpty() + byExt["aif"].orEmpty(), 2)
        return lame + plain + m4a + wav + flac
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
            val xml = grids[RekordboxXmlLite.matchKey(t.relPath.substringAfterLast('/'))]
            try {
                val d = LabDecoder.decode(ctx, access, t, buildDetail = false, cancelled = cancelled)
                val kind = GridOffsets.kindOf(ext(t), d.mp3, d.mime)
                val grid = xml?.let { BeatGrid.fromTempos(it.tempos, d.durationSec) }
                val r = grid?.let { GridOffsetEstimator.estimate(d.mono, d.count, d.sampleRate, it) }
                val row = Row(t, kind, r, grid?.fitConstant(), xml?.tempos?.size ?: 0, d.describeFormat(), d.sampleRate)
                rows.add(row)
                log.line(
                    "  [${kind.label}] ${t.row.title.take(44)} · ${row.format} · ${row.anchors} mốc TEMPO · " +
                        (r?.describe() ?: "không đo được (ít kick?)") +
                        (row.fit?.let { " · BPM khớp %.3f (lệch tối đa %.1f ms)".format(it.bpm, it.maxErrorMs) } ?: ""),
                )
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log.line("  [${ext(t)}] ${t.row.title.take(44)} · lỗi ${e.message ?: e.javaClass.simpleName}")
            }
        }
        summary(rows)
        return rows
    }

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    private fun summary(rows: List<Row>) {
        val ok = rows.filter { it.result != null }
        if (ok.isEmpty()) {
            log.line("Đo lệch grid: không bài nào đo được")
            return
        }
        val lossless = ok.filter { it.kind == GridOffsets.Kind.LOSSLESS }.map { it.result!!.medianMs }
        // Chưa có bài lossless thì dùng mốc lần đo trước (+1,2 ms).
        val base = if (lossless.isNotEmpty()) median(lossless) else 1.2
        log.line(
            "Tổng kết lệch grid XML so với audio Android (dương = audio muộn hơn grid) · mốc lossless %+.1f ms%s:".format(
                base, if (lossless.isEmpty()) " (lấy từ lần đo trước)" else " (${lossless.size} bài)",
            ),
        )
        ok.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal }).forEach { (kind, list) ->
            val med = median(list.map { it.result!!.medianMs })
            val rel = med - base
            val rate = list.first().sampleRate
            val table = GridOffsets.seconds(kind, rate) * 1000
            log.line(
                "  %s: %d bài · trung vị %+.1f ms (từ %+.1f tới %+.1f) · so với lossless %+.1f ms (≈ %+d mẫu ở %d Hz) · bảng bù %+.1f ms → còn lệch %+.1f ms · trôi %+.2f ms/phút".format(
                    kind.label, list.size, med, list.minOf { it.result!!.medianMs }, list.maxOf { it.result!!.medianMs },
                    rel, Math.round(rel / 1000 * rate), rate, table, rel - table, median(list.map { it.result!!.driftMsPerMin }),
                ),
            )
        }
    }
}
