package com.xdj.flx10test.sd

/**
 * Chọn số stem giải mã song song.
 *
 * Ở 0.15.0, `StemLoader.loadScoped` giải mã 4 stem lần lượt, mỗi stem ~20 s với bài 5 phút. Stems còn chỉ bắt đầu
 * sau khi `loadForPerformance` (nạp nền bài gốc, ~16 s) xong, và bị bỏ qua nếu deck đang phát.
 * LOG: "stems đã nạp sau 79751 ms".
 *
 * Cách mới: bắt đầu ngay sau READY và chạy song song trong giới hạn codec của `PerformanceResources`
 * (`codecLimit=3`, `cueReserve=1`). Khi có deck đang phát thì chạy ít luồng hơn và nghỉ ngắn giữa các mẻ,
 * để luồng audio không bị thiếu CPU.
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

    fun plan(
        stems: Int,
        codecLimit: Int,
        codecsInUse: Int,
        cueReserve: Int,
        anyDeckPlaying: Boolean,
        cpuCores: Int,
    ): Plan {
        if (stems <= 0) return Plan(0, 0, true)
        val free = (codecLimit - codecsInUse - cueReserve).coerceAtLeast(1)
        val cap = if (anyDeckPlaying) 2 else 3
        val byCpu = (cpuCores / 2).coerceAtLeast(1)
        val parallel = minOf(free, cap, byCpu, stems).coerceAtLeast(1)
        return Plan(parallel = parallel, pauseMs = if (anyDeckPlaying) 2L else 0L, startAfterReady = true)
    }
}
