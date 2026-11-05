package com.xdj.flx10test.wave

import java.io.InputStream
import java.util.Locale

/**
 * Bù lệch grid rekordbox theo định dạng file, vì bộ giải mã của Android và của rekordbox khác nhau.
 *
 * Số đo FLX10 Lab trên tablet (`LAB_20261105-200628.log`, "Đo lệch grid", dương = audio muộn hơn grid):
 * - WAV ×5: +1,0 · +3,3 · +1,2 · +2,3 · +0,7; FLAC ×2: +1,4 · −5,4. Mốc chuẩn lossless = **+1,2 ms**
 *   (độ trễ của đỉnh onset trong phép đo, không phải lệch thật).
 * - MP3 không Xing/LAME ×5 (44,1 kHz): −8,8 · −7,5 · −8,5 · −3,4 · −8,0. Trung vị −8,0, nên **−9,2 ms** so với lossless.
 * - M4A/AAC ×1 (encoder-delay 2112): +12,0, nên **+10,8 ms** so với lossless (mới 1 bài).
 * - MP3 có LAME: chưa đo; tạm dùng như MP3 không LAME.
 *
 * Bù = dời grid đi `seconds(...)` (`grid.shifted(seconds)`). Âm = grid sớm hơn.
 * Lưu theo số mẫu để đổi đúng khi tần số mẫu khác 44,1 kHz (lệch do bộ giải mã tính theo mẫu).
 */
object GridOffsets {
    enum class Kind(val label: String) {
        LOSSLESS("WAV/AIFF/FLAC/ALAC"),
        MP3_PLAIN("MP3 không LAME"),
        MP3_LAME("MP3 có LAME"),
        AAC("M4A/AAC"),
        OTHER("định dạng khác"),
    }

    data class Entry(
        /** Số mẫu dời grid (âm = sớm hơn). */
        val samples: Int,
        /** Đã đo trên tablet chưa (false = tạm). */
        val measured: Boolean,
        val note: String,
    )

    val DEFAULTS: Map<Kind, Entry> = mapOf(
        Kind.LOSSLESS to Entry(0, true, "WAV ×5, FLAC ×2: lệch +1,2 ms (mốc chuẩn)"),
        Kind.MP3_PLAIN to Entry(-406, true, "MP3 ×5: −9,2 ms so với lossless (44,1 kHz)"),
        Kind.MP3_LAME to Entry(-406, false, "chưa đo, tạm như MP3 không LAME"),
        Kind.AAC to Entry(476, false, "M4A ×1: +10,8 ms so với lossless, cần đo thêm"),
        Kind.OTHER to Entry(0, false, "chưa đo"),
    )

    /** Loại nguồn từ đuôi file, thông tin LAME và MIME (`audio/alac` trong .m4a là lossless). */
    fun kindOf(ext: String, mp3: Mp3Gapless.Info? = null, mime: String? = null): Kind {
        val e = ext.lowercase(Locale.ROOT).removePrefix(".")
        if (mime != null && (mime.equals("audio/alac", true) || mime.equals("audio/raw", true) || mime.equals("audio/flac", true))) return Kind.LOSSLESS
        return when (e) {
            "wav", "aif", "aiff", "flac", "alac" -> Kind.LOSSLESS
            "mp3" -> if (mp3?.hasLame == true) Kind.MP3_LAME else Kind.MP3_PLAIN
            "m4a", "aac", "mp4" -> Kind.AAC
            else -> Kind.OTHER
        }
    }

    /**
     * Số giây dời grid cho [kind] ở [sampleRate].
     * [overridesMs]: giá trị người dùng chỉnh tay trong ⚙ (ms), ưu tiên hơn bảng.
     */
    fun seconds(kind: Kind, sampleRate: Int, overridesMs: Map<Kind, Double> = emptyMap()): Double {
        overridesMs[kind]?.let { return it / 1000.0 }
        val entry = DEFAULTS[kind] ?: return 0.0
        if (sampleRate <= 0) return 0.0
        return entry.samples.toDouble() / sampleRate
    }

    fun describe(kind: Kind, sampleRate: Int, overridesMs: Map<Kind, Double> = emptyMap()): String {
        val ms = seconds(kind, sampleRate, overridesMs) * 1000
        val src = when {
            overridesMs.containsKey(kind) -> "chỉnh tay"
            DEFAULTS[kind]?.measured == true -> "đã đo"
            else -> "tạm"
        }
        return "%+.1f ms (%s, %s)".format(ms, kind.label, src)
    }
}

/** Đọc phần đầu MP3 để lấy LAME: bỏ qua thẻ ID3 (ảnh bìa có thể vài trăm KB) bằng skip, rồi chỉ đọc [frameBytes]. */
object Mp3Head {
    fun read(input: InputStream, frameBytes: Int = 16 * 1024): ByteArray {
        val head = ByteArray(10)
        val n = readFully(input, head, 0, 10)
        if (n < 10) return head.copyOf(n)
        var prefix = head
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
            val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
            skipFully(input, (size + footer).toLong())
            prefix = ByteArray(0)
        }
        val rest = ByteArray(frameBytes)
        val m = readFully(input, rest, 0, frameBytes)
        return prefix + rest.copyOf(m)
    }

    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return
                left--
            } else {
                left -= s
            }
        }
    }
}
