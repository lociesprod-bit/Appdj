package com.xdj.flx10test.lab

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.Normalizer

/**
 * Đọc rekordbox XML kiểu stream, chỉ lấy trường cần để HIỂN THỊ trong Lab
 * (app chính đã có `RekordboxXml` đầy đủ grid/cue/playlist).
 */
object RekordboxXmlLite {
    data class Track(
        val name: String,
        val artist: String,
        val bpm: Double,
        val tonality: String,
        val rating: Int,
        val totalTimeSec: Int,
        val genre: String,
        val comments: String,
        val fileName: String,
    )

    data class Result(val tracks: List<Track>, val product: String)

    fun parse(input: InputStream): Result {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        val out = ArrayList<Track>(4096)
        var product = ""
        var inCollection = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "PRODUCT" -> product = "${parser.attr("Name")} ${parser.attr("Version")}".trim()
                    "COLLECTION" -> inCollection = true
                    "TRACK" -> if (inCollection && parser.depth == 3) {
                        val location = parser.attr("Location")
                        out.add(
                            Track(
                                name = parser.attr("Name"),
                                artist = parser.attr("Artist"),
                                bpm = parser.attr("AverageBpm").toDoubleOrNull() ?: 0.0,
                                tonality = parser.attr("Tonality"),
                                rating = ratingStars(parser.attr("Rating").toIntOrNull() ?: 0),
                                totalTimeSec = parser.attr("TotalTime").toIntOrNull() ?: 0,
                                genre = parser.attr("Genre"),
                                comments = parser.attr("Comments"),
                                fileName = fileNameOf(location),
                            ),
                        )
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "COLLECTION") {
                inCollection = false
            }
            event = parser.next()
        }
        return Result(out, product)
    }

    /** Mọi mốc TEMPO của một bài trong XML. */
    data class TrackGrid(val tempos: List<com.xdj.flx10test.wave.BeatGrid.Companion.Tempo>, val averageBpm: Double, val kind: String)

    /**
     * Đọc **mọi** mốc TEMPO của các bài có khoá tên file trong [keys] (xem [matchKey]).
     * App 0.15.0 chỉ dùng mốc đầu. Đọc kiểu stream, bỏ qua bài không cần.
     */
    fun grids(input: InputStream, keys: Set<String>): Map<String, TrackGrid> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        val out = HashMap<String, TrackGrid>()
        var inCollection = false
        var current: String? = null
        var avg = 0.0
        var kind = ""
        val tempos = ArrayList<com.xdj.flx10test.wave.BeatGrid.Companion.Tempo>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "COLLECTION" -> inCollection = true
                    "TRACK" -> if (inCollection && parser.depth == 3) {
                        val key = matchKey(fileNameOf(parser.attr("Location")))
                        current = if (key in keys && key !in out) key else null
                        avg = parser.attr("AverageBpm").toDoubleOrNull() ?: 0.0
                        kind = parser.attr("Kind")
                        tempos.clear()
                    }
                    "TEMPO" -> if (current != null) {
                        val bpm = parser.attr("Bpm").toDoubleOrNull()
                        val at = parser.attr("Inizio").toDoubleOrNull()
                        val metro = parser.attr("Metro").substringBefore('/').toIntOrNull() ?: 4
                        val battito = parser.attr("Battito").toIntOrNull() ?: 1
                        if (bpm != null && at != null) {
                            tempos.add(com.xdj.flx10test.wave.BeatGrid.Companion.Tempo(at, bpm, battito, metro.coerceIn(1, 16)))
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "TRACK" -> if (inCollection && parser.depth == 3) {
                        current?.let { out[it] = TrackGrid(tempos.toList(), avg, kind) }
                        current = null
                        if (out.size == keys.size) return out
                    }
                    "COLLECTION" -> inCollection = false
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun XmlPullParser.attr(name: String): String = getAttributeValue(null, name) ?: ""

    /** rekordbox lưu Rating 0/51/102/153/204/255. */
    fun ratingStars(raw: Int): Int = ((raw + 25) / 51).coerceIn(0, 5)

    /** Tên file (đã giải mã %XX theo UTF-8) từ `file://localhost/Users/.../All%20Music%20DJ/x.mp3`. */
    fun fileNameOf(location: String): String {
        if (location.isEmpty()) return ""
        return percentDecode(location.substringAfterLast('/'))
    }

    /** Giải mã %XX; không đổi '+' thành dấu cách (khác URLDecoder). */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString("UTF-8")
    }

    /** Khoá ghép tên file: NFC + chữ thường (macOS ghi tên NFD lên thẻ, XML có thể là NFC). */
    fun matchKey(fileName: String): String = Normalizer.normalize(fileName, Normalizer.Form.NFC).lowercase()
}
