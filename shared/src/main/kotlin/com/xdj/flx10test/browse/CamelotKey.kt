package com.xdj.flx10test.browse

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Key trên vòng Camelot: [number] 1–12, [minor] = true là "A" (thứ), false là "B" (trưởng).
 *
 * Tương thích với `MusicalKey` của app 0.15.0: key index 0–11 là giọng trưởng C…B,
 * 12–23 là giọng thứ C…B (C, C♯, D, E♭, E, F, F♯, G, A♭, A, B♭, B).
 */
data class CamelotKey(val number: Int, val minor: Boolean) {
    init {
        require(number in 1..12) { "Camelot number $number" }
    }

    val label: String get() = "$number${if (minor) "A" else "B"}"

    /** Tên nốt, ví dụ "A♭m", "C". */
    val noteName: String get() = toKeyIndex().let { NOTES[it % 12] + if (minor) "m" else "" }

    /** Lên 1 nửa cung = +7 trên vòng Camelot (xuống 1 nửa cung = −7 ≡ +5). */
    fun transpose(semitones: Int): CamelotKey {
        val steps = Math.floorMod(7 * semitones, 12)
        return copy(number = Math.floorMod(number - 1 + steps, 12) + 1)
    }

    /** Key index của `MusicalKey` (0–23). */
    fun toKeyIndex(): Int {
        val table = if (minor) MINOR else MAJOR
        val note = table.indexOf(number)
        return if (minor) 12 + note else note
    }

    override fun toString(): String = label

    companion object {
        private val NOTES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

        // Giống MusicalKey: số Camelot theo nốt C…B.
        private val MAJOR = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        private val MINOR = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)

        private val CAMELOT = Regex("^(1[0-2]|0?[1-9])\\s*([AaBb])$")
        private val OPEN_KEY = Regex("^(1[0-2]|0?[1-9])\\s*([mMdD])$")
        private val MUSICAL = Regex("^([A-Ga-g])\\s*([#♯b♭]?)\\s*(m|min|minor|M|maj|major)?$")
        private val LETTER = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        fun fromKeyIndex(index: Int): CamelotKey? {
            if (index !in 0..23) return null
            val minor = index >= 12
            val n = (if (minor) MINOR else MAJOR)[index % 12]
            return CamelotKey(n, minor)
        }

        fun fromNote(noteIndex: Int, minor: Boolean): CamelotKey =
            CamelotKey((if (minor) MINOR else MAJOR)[Math.floorMod(noteIndex, 12)], minor)

        /**
         * Đọc key từ chuỗi kiểu rekordbox/Serato/Traktor:
         * "11A", "8B", "08A", "Am", "A♭m", "Abm", "G#m", "F#", "Gb", "Bbmin", "C major", Open Key "6m"/"1d",
         * và nhãn của app "1A · A♭m". Trả null nếu không đọc được.
         */
        fun parse(raw: String?): CamelotKey? {
            if (raw == null) return null
            var s = raw.trim().removePrefix("≈").trim()
            if (s.isEmpty() || s == "—" || s == "-") return null
            // Nhãn "1A · A♭m" của MusicalKey.label(): lấy phần trước " · ".
            s = s.substringBefore('·').trim()
            CAMELOT.matchEntire(s)?.let { m ->
                return CamelotKey(m.groupValues[1].toInt(), m.groupValues[2].equals("A", ignoreCase = true))
            }
            OPEN_KEY.matchEntire(s)?.let { m ->
                val n = m.groupValues[1].toInt()
                val minor = m.groupValues[2].equals("m", ignoreCase = true)
                return CamelotKey(Math.floorMod(n - 1 + 7, 12) + 1, minor)
            }
            val compact = s.replace(" ", "")
            MUSICAL.matchEntire(compact)?.let { m ->
                val letter = m.groupValues[1][0]
                // "b" thường chỉ là dấu giáng khi đứng sau một chữ cái nốt; "bm" (B thứ viết thường) cũng chấp nhận.
                val base = LETTER[letter.uppercaseChar()] ?: return null
                val accidental = when (m.groupValues[2]) {
                    "#", "♯" -> 1
                    "b", "♭" -> -1
                    else -> 0
                }
                val quality = m.groupValues[3]
                val minor = quality == "m" || quality.equals("min", true) || quality.equals("minor", true)
                return fromNote(base + accidental, minor)
            }
            return null
        }

        /**
         * Số nửa cung khi phát với tỉ lệ tempo [ratio] mà Master Tempo đang TẮT
         * (ví dụ +6% ≈ +1 nửa cung). Làm tròn tới nửa cung gần nhất.
         */
        fun semitonesFor(ratio: Double): Int {
            if (!(ratio > 0.0) || ratio.isInfinite()) return 0
            return (12.0 * ln(ratio) / ln(2.0)).roundToInt()
        }
    }
}

/** Mức hợp key so với deck MASTER, như rekordbox tô xanh cột Key. */
enum class KeyMatch {
    SAME,
    COMPATIBLE,
    NONE;

    companion object {
        /**
         * - [SAME]: cùng key.
         * - [COMPATIBLE]: cùng chữ, số ±1 (vòng 12↔1); hoặc cùng số, khác chữ (A↔B, cặp trưởng/thứ tương ứng).
         * - [NONE]: còn lại, hoặc thiếu key.
         */
        fun of(master: CamelotKey?, track: CamelotKey?): KeyMatch {
            if (master == null || track == null) return NONE
            if (master == track) return SAME
            if (master.number == track.number) return COMPATIBLE
            if (master.minor == track.minor) {
                val d = Math.floorMod(track.number - master.number, 12)
                if (d == 1 || d == 11) return COMPATIBLE
            }
            return NONE
        }
    }
}

/**
 * Key "đang nghe" của deck MASTER: nếu Master Tempo (MT) tắt thì key dịch theo tempo.
 * [baseKey] là key gốc của bài (TrackMetadata.keyIndex), [tempoRatio] = tốc độ hiện tại / tốc độ gốc.
 */
object MasterKey {
    fun effective(baseKey: CamelotKey?, tempoRatio: Double, masterTempo: Boolean): CamelotKey? {
        if (baseKey == null) return null
        if (masterTempo) return baseKey
        return baseKey.transpose(CamelotKey.semitonesFor(tempoRatio))
    }
}
