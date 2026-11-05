package com.xdj.flx10test.lab

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Đọc file `.serato-stems` (định dạng như `SeratoStems.extract` của app 0.15.0):
 * header `srtshead` (16 byte: version 1.x, count=4, samples, rate), rồi 4 chunk `stem`
 * (size, type 0–3, dữ liệu MP3 XOR 0x26). Tách ra 4 file MP3 tạm để giải mã.
 */
object SeratoStemsFile {
    data class Stem(val type: Int, val file: File)
    data class Extracted(val rate: Int, val samples: Long, val stems: List<Stem>, val bytes: Long)

    // Thứ tự type giống app 0.15.0: 0 VOCAL, 1 BASS, 2 DRUMS, 3 MELODY.
    private val NAMES = arrayOf("VOCAL", "BASS", "DRUMS", "MELODY")
    fun typeName(type: Int): String = NAMES.getOrElse(type) { "STEM$type" }

    fun extract(stream: InputStream, folder: File): Extracted {
        folder.mkdirs()
        val created = ArrayList<File>()
        try {
            val input = DataInputStream(BufferedInputStream(stream, 1 shl 16))
            require(tag(input, 8) == "srtshead" && u32(input) == 16L) { "Không phải file stems Serato (srtshead)" }
            val major = input.readUnsignedShort()
            input.readUnsignedShort()
            require(major == 1) { "Phiên bản stems $major chưa hỗ trợ" }
            val count = u32(input)
            val samples = u32(input)
            val rate = u32(input).toInt()
            require(count == 4L) { "Số stem $count" }
            val result = ArrayList<Stem>()
            var total = 0L
            val buffer = ByteArray(1 shl 16)
            repeat(4) {
                require(tag(input, 4) == "stem") { "Chunk stems lỗi" }
                val size = u32(input) - 4
                val type = u32(input).toInt()
                require(size in 4..(128L shl 20) && type in 0..3) { "Chunk stems lỗi kích thước" }
                val file = File(folder, "stem-$type.mp3")
                created.add(file)
                FileOutputStream(file).use { out ->
                    var left = size
                    while (left > 0) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) throw EOFException("File stems bị thiếu dữ liệu")
                        for (i in 0 until n) buffer[i] = (buffer[i].toInt() xor 0x26).toByte()
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
                total += size
                result.add(Stem(type, file))
            }
            return Extracted(rate, samples, result.sortedBy { it.type }, total)
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }

    private fun tag(input: DataInputStream, n: Int): String {
        val b = ByteArray(n)
        input.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun u32(input: DataInputStream): Long = input.readInt().toLong() and 0xffffffffL
}
