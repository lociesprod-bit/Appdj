package com.xdj.flx10test.sd

/**
 * Chọn số stem giải mã song song.
 *
 * Ở 0.15.0, `StemLoader.loadScoped` giải mã 4 stem lần lượt, mỗi stem ~20 s với bài 5 phút. Stems còn chỉ bắt đầu
 * sau khi `loadForPerformance` (nạp nền bài gốc, ~16 s) xong, và bị bỏ qua nếu deck đang phát.
 * LOG: "stems đã nạp sau 79751 ms".
 *
 * Số đo FLX10 Lab trên tablet (Xiaomi 2405CRPFDG, 8 nhân), 4 stem MP3, bài 246 s:
 * 1 luồng 25,1 s · 2 luồng 13,6 s · 3 luồng 15,1 s · 4 luồng 10,6 s.
 * 3 luồng chậm hơn 2 vì 4 stem chia thành 2 đợt (3 + 1), nên chỉ dùng số luồng chia hết số stem: 1, 2 hoặc 4.
 *
 * Decoder MP3 là phần mềm (`c2.android.mp3.decoder`), không chiếm slot codec phần cứng. Vì vậy stems dùng pool
 * riêng và không lấy vào `codecLimit` (3) của `PerformanceResources`, phần dành cho bài gốc và cửa sổ CUE.
 */
object StemDecodeScheduler {
    data class Plan(
        /** Số stem giải mã cùng lúc. */
        val parallel: Int,
        /** Nghỉ giữa các mẻ PCM (ms) để nhường CPU cho luồng audio. */
        val pauseMs: Long,
        /** Bắt đầu ngay sau READY (không đợi nạp nền bài gốc xong). */
        val startAfterReady: Boolean,
    )

    /** Số luồng tối đa khi không deck nào đang phát. */
    const val IDLE_MAX = 4

    /** Số luồng tối đa khi có deck đang phát (để dành CPU cho luồng audio). */
    const val PLAYING_MAX = 2

    /**
     * @param stems số stem cần giải mã (thường là 4).
     * @param anyDeckPlaying có deck nào đang phát không.
     * @param cpuCores số nhân CPU (`Runtime.availableProcessors()`).
     */
    fun plan(stems: Int, anyDeckPlaying: Boolean, cpuCores: Int): Plan {
        if (stems <= 0) return Plan(0, 0, true)
        val cap = if (anyDeckPlaying) PLAYING_MAX else IDLE_MAX
        // Chừa ít nhất 2 nhân cho luồng audio và UI.
        val byCpu = (cpuCores - 2).coerceAtLeast(1)
        val limit = minOf(cap, byCpu, stems)
        return Plan(parallel = evenSplit(stems, limit), pauseMs = if (anyDeckPlaying) 2L else 0L, startAfterReady = true)
    }

    /** Số luồng lớn nhất ≤ [limit] chia hết [stems], để mọi luồng chạy đúng 1 đợt (4 stem: 1, 2 hoặc 4). */
    fun evenSplit(stems: Int, limit: Int): Int {
        if (stems <= 0) return 0
        for (n in limit.coerceIn(1, stems) downTo 1) {
            if (stems % n == 0) return n
        }
        return 1
    }
}
