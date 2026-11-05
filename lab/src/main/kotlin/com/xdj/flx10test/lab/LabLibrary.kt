package com.xdj.flx10test.lab

import android.content.Context
import com.xdj.flx10test.browse.BrowserRow
import com.xdj.flx10test.sd.CachedDir
import com.xdj.flx10test.sd.CachedFile
import com.xdj.flx10test.sd.ParallelWalker
import com.xdj.flx10test.sd.SdAccess
import com.xdj.flx10test.sd.ScanSnapshot
import com.xdj.flx10test.sd.SdScanCache
import java.io.File
import java.util.Locale

/** Một bài trên thẻ: hàng hiển thị + đường dẫn/ID để đo resolve và stems. */
class LabTrack(
    val row: BrowserRow,
    val relPath: String,
    val documentId: String,
    val size: Long,
    val stemsId: String?,
    val stemsName: String?,
)

/**
 * Thư viện thẻ SD của Lab: quét `All Music DJ/`, ghép rekordbox XML trong `XML/`, lưu đệm bằng [SdScanCache].
 * Mọi bước đều ghi thời gian vào LOG.
 */
class LabLibrary(ctx: Context, val access: SdAccess, private val log: LabLog) {
    private val source get() = access.source
    private val saf get() = access.saf
    @Volatile var tracks: List<LabTrack> = emptyList()
        private set

    var xmlLabel: String = ""
        private set

    /** id thư mục XML/ (theo nguồn đang dùng), để màn Sóng & grid đọc lại TEMPO. */
    @Volatile var xmlDirId: String? = null
        private set

    /** Mở file rekordbox XML mới nhất trong XML/ (tên, luồng đọc). */
    fun openNewestXml(): Pair<String, java.io.InputStream>? {
        val dir = xmlDirId ?: source.list(access.rootId).firstOrNull { it.directory && it.name == "XML" }?.id ?: return null
        val entry = newestXml(dir) ?: return null
        return entry.name to access.openInputStream(entry.id)
    }

    // Đệm riêng cho từng chế độ: id của FILE (đường dẫn) và SAF (documentId) khác nhau.
    private val cacheFile = File(
        ctx.filesDir,
        "sd-cache/" + Integer.toHexString(saf.treeUri.toString().hashCode()) + "-" + access.mode.name.lowercase() + ".bin",
    )

    private fun diag() = if (access.mode == SdAccess.Mode.SAF) " · " + saf.diagnostics() else ""

    private fun resetCounters() {
        saf.queries.set(0)
        saf.queryNanos.set(0)
    }

    data class Summary(val tracks: Int, val withStems: Int, val xmlMatched: Int, val xmlTracks: Int, val millis: Long)

    /** Quét đầy đủ (giống "↻ Quét"). */
    fun scan(): Summary {
        val t0 = System.nanoTime()
        resetCounters()
        val rootId = access.rootId
        val root = source.list(rootId)
        val tRoot = ms(t0)
        val music = root.firstOrNull { it.directory && it.name == MUSIC } ?: error("Không thấy thư mục '$MUSIC' trong thư mục đã chọn")
        val xmlDir = root.firstOrNull { it.directory && it.name == "XML" }
        xmlDirId = xmlDir?.id

        val t1 = System.nanoTime()
        val (dirs, stats) = ParallelWalker(source, THREADS).walk(music.id, MUSIC, music.modified)
        val tWalk = ms(t1)
        log.line("Liệt kê $MUSIC bằng ${access.mode}: ${stats.dirs} thư mục · ${stats.entries} mục · $tWalk ms ($THREADS luồng)${diag()}")

        val t2 = System.nanoTime()
        val xml = readXml(xmlDir?.id)
        val tXml = ms(t2)

        val snapshot = ScanSnapshot.fromListings(
            rootId, MUSIC, dirs,
            mapOf(
                "xmlStamp" to (xml?.stamp ?: ""),
                "xmlMeta" to (xml?.let { encodeMeta(it.tracks) } ?: ""),
                "xmlLabel" to (xml?.label ?: ""),
                "xmlDir" to (xmlDir?.id ?: ""),
            ),
        )
        val t3 = System.nanoTime()
        val built = build(snapshot)
        val tBuild = ms(t3)
        val t4 = System.nanoTime()
        SdScanCache.save(cacheFile, snapshot)
        val tSave = ms(t4)
        val total = ms(t0)
        log.line(
            "Quét thẻ (${access.mode}): ${built.tracks} bài · ${built.withStems} có stems · XML khớp ${built.xmlMatched}/${built.xmlTracks} · " +
                "tổng $total ms (gốc $tRoot · liệt kê $tWalk · XML $tXml · ghép $tBuild · lưu đệm $tSave)",
        )
        return built.copy(millis = total)
    }

    /**
     * Mở từ đệm: hiện danh sách ngay ([onReady]), rồi kiểm tra thẻ trong cùng luồng (gọi từ luồng nền).
     * Trả null nếu chưa có đệm.
     */
    fun openCached(onReady: (Summary) -> Unit): Summary? {
        val t0 = System.nanoTime()
        resetCounters()
        val cached = SdScanCache.load(cacheFile, access.rootId) ?: return null
        val tLoad = ms(t0)
        val ready = build(cached)
        val tReady = ms(t0)
        onReady(ready.copy(millis = tReady))

        val t1 = System.nanoTime()
        val refreshed = SdScanCache.refresh(cached, source, THREADS)
        val tCheck = ms(t1)
        val t2 = System.nanoTime()
        val xmlDirId = cached.extra["xmlDir"].orEmpty()
        this.xmlDirId = xmlDirId.ifEmpty { null }
        val stamp = if (xmlDirId.isNotEmpty()) newestXml(xmlDirId)?.let { stampOf(it) }.orEmpty() else ""
        var snapshot = refreshed.snapshot
        var xmlState = "không đổi"
        if (stamp != cached.extra["xmlStamp"].orEmpty()) {
            val xml = readXml(xmlDirId.ifEmpty { null })
            snapshot = snapshot.copy(
                extra = snapshot.extra + mapOf(
                    "xmlStamp" to (xml?.stamp ?: ""),
                    "xmlMeta" to (xml?.let { encodeMeta(it.tracks) } ?: ""),
                    "xmlLabel" to (xml?.label ?: ""),
                ),
            )
            xmlState = "đọc lại"
        }
        val tXml = ms(t2)
        var result = ready
        if (!refreshed.unchanged || xmlState != "không đổi") {
            result = build(snapshot)
            SdScanCache.save(cacheFile, snapshot)
        }
        val total = ms(t0)
        log.line(
            "Mở lại có đệm (${access.mode}): đọc đệm $tLoad ms · danh sách sẵn sàng sau $tReady ms (${ready.tracks} bài) · " +
                "kiểm tra ${refreshed.checked} thư mục $tCheck ms (liệt kê lại ${refreshed.relisted}, mới ${refreshed.added}, mất ${refreshed.removed}) · " +
                "XML $xmlState $tXml ms · tổng $total ms${diag()}",
        )
        return result.copy(millis = total)
    }

    private class Xml(val tracks: List<RekordboxXmlLite.Track>, val stamp: String, val label: String)

    private fun newestXml(xmlDirId: String) =
        source.list(xmlDirId).filter { !it.directory && !it.name.startsWith(".") && it.name.endsWith(".xml", true) }.maxByOrNull { it.modified }

    private fun stampOf(e: com.xdj.flx10test.sd.DirEntry) = "${e.id}\t${e.size}\t${e.modified}"

    private fun readXml(xmlDirId: String?): Xml? {
        if (xmlDirId == null) {
            log.line("XML: không có thư mục XML/")
            return null
        }
        val t0 = System.nanoTime()
        val entry = newestXml(xmlDirId)
        if (entry == null) {
            log.line("XML: thư mục XML/ trống")
            return null
        }
        val parsed = access.openInputStream(entry.id).use { RekordboxXmlLite.parse(it) }
        val label = "${entry.name} (${parsed.product}, ${parsed.tracks.size} bài)"
        log.line("XML: $label · ${entry.size / 1024} KB · đọc ${ms(t0)} ms")
        return Xml(parsed.tracks, stampOf(entry), label)
    }

    private fun build(snapshot: ScanSnapshot): Summary {
        xmlLabel = snapshot.extra["xmlLabel"].orEmpty()
        val meta = decodeMeta(snapshot.extra["xmlMeta"].orEmpty())
        val byName = HashMap<String, RekordboxXmlLite.Track>(meta.size * 2)
        meta.forEach { byName.putIfAbsent(RekordboxXmlLite.matchKey(it.fileName), it) }
        val out = ArrayList<LabTrack>(snapshot.fileCount)
        var matched = 0
        var stems = 0
        var id = 0L
        for (dir in snapshot.dirs) {
            val stemsByBase = HashMap<String, CachedFile>()
            dir.files.filter { STEMS.containsMatchIn(it.name) }.forEach { stemsByBase[STEMS.replace(it.name, "")] = it }
            for (f in dir.files) {
                val ext = f.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in AUDIO) continue
                val m = byName[RekordboxXmlLite.matchKey(f.name)]
                if (m != null) matched++
                val stem = stemsByBase[f.name.substringBeforeLast('.')]
                if (stem != null) stems++
                val row = BrowserRow.fromRekordbox(
                    id = id++,
                    title = m?.name?.ifBlank { null } ?: f.name.substringBeforeLast('.'),
                    artist = m?.artist.orEmpty(),
                    bpm = m?.bpm ?: 0.0,
                    key = m?.tonality.orEmpty(),
                    myTags = null,
                    comment = m?.comments.orEmpty(),
                    genre = m?.genre.orEmpty(),
                    durationMs = (m?.totalTimeSec ?: 0) * 1000L,
                    rating = m?.rating ?: 0,
                    hasStems = stem != null,
                )
                out.add(LabTrack(row, relPath(dir, f), f.id, f.size, stem?.id, stem?.name))
            }
        }
        tracks = out
        return Summary(out.size, stems, matched, meta.size, 0)
    }

    private fun relPath(dir: CachedDir, f: CachedFile) = if (dir.path.isEmpty()) f.name else dir.path + "/" + f.name

    companion object {
        const val MUSIC = "All Music DJ"
        const val THREADS = 3
        val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "ogg", "opus", "alac")
        val STEMS = Regex("\\.\\d+\\.\\d+\\.serato-stems$", RegexOption.IGNORE_CASE)

        private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        fun encodeMeta(tracks: List<RekordboxXmlLite.Track>): String = buildString {
            for (t in tracks) {
                append(clean(t.fileName)).append('\t').append(clean(t.name)).append('\t').append(clean(t.artist)).append('\t')
                append(t.bpm).append('\t').append(clean(t.tonality)).append('\t').append(t.rating).append('\t')
                append(t.totalTimeSec).append('\t').append(clean(t.genre)).append('\t').append(clean(t.comments)).append('\n')
            }
        }

        fun decodeMeta(s: String): List<RekordboxXmlLite.Track> {
            if (s.isEmpty()) return emptyList()
            return s.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 9) {
                    null
                } else {
                    RekordboxXmlLite.Track(
                        name = p[1], artist = p[2], bpm = p[3].toDoubleOrNull() ?: 0.0, tonality = p[4],
                        rating = p[5].toIntOrNull() ?: 0, totalTimeSec = p[6].toIntOrNull() ?: 0,
                        genre = p[7], comments = p[8], fileName = p[0],
                    )
                }
            }.toList()
        }
    }
}
