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
