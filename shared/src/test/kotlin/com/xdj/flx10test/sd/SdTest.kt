package com.xdj.flx10test.sd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SafDocIdTest {
    @Test
    fun childOfFolderTree() {
        assertEquals(
            "3661-3634:Music/All Music DJ/11A - Ai Chung Tinh Duoc Mai HD.wav",
            SafDocId.child("3661-3634:Music", "All Music DJ/11A - Ai Chung Tinh Duoc Mai HD.wav"),
        )
    }

    @Test
    fun childOfVolumeRoot() {
        assertEquals("3661-3634:Music/XML/a.xml", SafDocId.child("3661-3634:", "Music/XML/a.xml"))
        assertEquals("primary:Music/x.mp3", SafDocId.child("primary:Music/", "/x.mp3"))
    }

    @Test
    fun keepsSpecialCharactersVerbatim() {
        // Không URL-encode: buildDocumentUriUsingTree tự encode. Dấu cách, #, %, ; giữ nguyên.
        assertEquals("v:M/a #1 100% [x];y.mp3", SafDocId.child("v:M", "a #1 100% [x];y.mp3"))
        val nfd = Normalizer.normalize("Bên Trên Tầng Lầu.mp3", Normalizer.Form.NFD)
        assertEquals("v:M/All Music DJ/$nfd", SafDocId.child("v:M", "All Music DJ/$nfd"))
        assertFalse(SafDocId.child("v:M", "All Music DJ/$nfd") == SafDocId.child("v:M", "All Music DJ/" + Normalizer.normalize(nfd, Normalizer.Form.NFC)))
    }

    @Test
    fun rejectsUnsafeOrNonPathIds() {
        assertNull(SafDocId.child("msf:12345", "../x"))
        assertNull(SafDocId.child("v:M", "a/../../etc"))
        assertNull(SafDocId.child("v:M", ""))
        assertNull(SafDocId.child("12345", "a.mp3"))
        assertTrue(SafDocId.isPathShaped(SafDocId.EXTERNAL_STORAGE_AUTHORITY, "3661-3634:Music"))
        assertFalse(SafDocId.isPathShaped("com.android.providers.downloads.documents", "3661-3634:Music"))
        assertFalse(SafDocId.isPathShaped(SafDocId.EXTERNAL_STORAGE_AUTHORITY, "raw"))
    }

    @Test
    fun relativeIsInverse() {
        val id = SafDocId.child("3661-3634:Music", "All Music DJ/a.mp3")!!
        assertEquals("All Music DJ/a.mp3", SafDocId.relative("3661-3634:Music", id))
        assertEquals("", SafDocId.relative("3661-3634:Music", "3661-3634:Music"))
        assertNull(SafDocId.relative("3661-3634:Music", "3661-3634:Musical/a.mp3"))
        assertEquals("Music/a.mp3", SafDocId.relative("3661-3634:", "3661-3634:Music/a.mp3"))
    }

    @Test
    fun filePathForAllFilesAccess() {
        assertEquals("/storage/3661-3634/Music", SafDocId.filePath("3661-3634:Music"))
        assertEquals("/storage/emulated/0/Music/All Music DJ", SafDocId.filePath("primary:Music/All Music DJ"))
        assertEquals("/storage/3661-3634", SafDocId.filePath("3661-3634:"))
        assertNull(SafDocId.filePath("12345"))
    }
}

/** Cây thư mục giả trong bộ nhớ, id kiểu ExternalStorageProvider. */
class FakeSource : DirSource {
    val dirs = ConcurrentHashMap<String, MutableList<DirEntry>>()
    val mtimes = ConcurrentHashMap<String, Long>()
    val listCalls = AtomicInteger()
    val statCalls = AtomicInteger()
    val concurrent = AtomicInteger()
    @Volatile var maxConcurrent = 0
    var listDelayMs = 0L

    fun dir(id: String, mtime: Long = 1L) {
        dirs.putIfAbsent(id, mutableListOf())
        mtimes[id] = mtime
        val parent = id.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent.contains(':') && dirs.containsKey(parent)) {
            dirs[parent]!!.add(DirEntry(id, id.substringAfterLast('/'), true, 0, mtime))
        }
    }

    fun file(dirId: String, name: String, size: Long = 100) {
        dirs[dirId]!!.add(DirEntry("$dirId/$name", name, false, size, 5))
    }

    override fun list(id: String): List<DirEntry> {
        listCalls.incrementAndGet()
        val now = concurrent.incrementAndGet()
        synchronized(this) { if (now > maxConcurrent) maxConcurrent = now }
        try {
            if (listDelayMs > 0) Thread.sleep(listDelayMs)
            val entries = dirs[id] ?: throw FileNotFoundException(id)
            // mtime con thư mục luôn theo giá trị hiện tại
            return entries.map { if (it.directory) it.copy(modified = mtimes[it.id] ?: 0L) else it }
        } finally {
            concurrent.decrementAndGet()
        }
    }

    override fun stat(id: String): DirEntry? {
        statCalls.incrementAndGet()
        val m = mtimes[id] ?: return null
        return DirEntry(id, id.substringAfterLast('/'), true, 0, m)
    }
}

class ParallelWalkerTest {
    private fun library(): FakeSource {
        val s = FakeSource()
        s.dir("v:Music/All Music DJ")
        s.file("v:Music/All Music DJ", "a.mp3")
        s.file("v:Music/All Music DJ", "._a.mp3")
        s.file("v:Music/All Music DJ", ".DS_Store")
        s.file("v:Music/All Music DJ", "a.1.0.serato-stems")
        for (g in listOf("Afro House", "Deep House", "Techno", "Vina House")) {
            s.dir("v:Music/All Music DJ/$g")
            repeat(5) { s.file("v:Music/All Music DJ/$g", "$g $it.mp3") }
        }
        s.dir("v:Music/All Music DJ/Techno/Old")
        s.file("v:Music/All Music DJ/Techno/Old", "old.flac")
        return s
    }

    @Test
    fun walksWholeTreeSkippingJunk() {
        val s = library()
        val (dirs, stats) = ParallelWalker(s, threads = 3).walk("v:Music/All Music DJ", "All Music DJ")
        assertEquals(6, dirs.size)
        assertEquals(6, s.listCalls.get())
        val root = dirs.first { it.path == "All Music DJ" }
        assertEquals(listOf("a.mp3", "a.1.0.serato-stems"), root.entries.filter { !it.directory }.map { it.name })
        assertTrue(dirs.any { it.path == "All Music DJ/Techno/Old" })
        assertEquals(6 + 4 * 5 + 1 + 1, stats.entries) // gốc: 2 file + 4 thư mục; 20 bài; Techno/Old: 1 thư mục + 1 file
    }

    @Test
    fun listsDirectoriesConcurrently() {
        val s = library()
        s.listDelayMs = 40
        ParallelWalker(s, threads = 3).walk("v:Music/All Music DJ", "All Music DJ")
        assertTrue("max=${s.maxConcurrent}", s.maxConcurrent >= 2)
    }

    @Test(expected = FileNotFoundException::class)
    fun propagatesErrors() {
        ParallelWalker(FakeSource(), 2).walk("v:missing", "x")
    }
}

class SdScanCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun source(): FakeSource {
        val s = FakeSource()
        s.dir("v:M/All", 10)
        s.file("v:M/All", "a.mp3")
        s.dir("v:M/All/Deep", 20)
        s.file("v:M/All/Deep", "d1.mp3")
        s.dir("v:M/All/Techno", 30)
        s.file("v:M/All/Techno", "t1.mp3")
        return s
    }

    private fun snapshot(s: FakeSource): ScanSnapshot {
        val (dirs, _) = ParallelWalker(s, 2).walk("v:M/All", "All", s.mtimes["v:M/All"]!!)
        return ScanSnapshot.fromListings("v:M", "All", dirs, mapOf("xml" to "rekordbox.xml\t1\t2"))
    }

    @Test
    fun diffClassifiesDirectories() {
        val d = SdScanCache.diff(mapOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L), mapOf("a" to 1L, "b" to 5L, "c" to null))
        assertEquals(setOf("a"), d.unchanged)
        assertEquals(setOf("b", "d"), d.changed)
        assertEquals(setOf("c"), d.removed)
        // mtime 0 (provider không báo) → luôn coi là đổi.
        assertEquals(setOf("z"), SdScanCache.diff(mapOf("z" to 0L), mapOf("z" to 0L)).changed)
    }

    @Test
    fun unchangedCardOnlyStats() {
        val s = source()
        val snap = snapshot(s)
        s.listCalls.set(0)
        val r = SdScanCache.refresh(snap, s)
        assertTrue(r.unchanged)
        assertEquals(0, s.listCalls.get())
        assertEquals(3, s.statCalls.get())
        assertEquals(snap.fileCount, r.snapshot.fileCount)
    }

    @Test
    fun relistsOnlyChangedDirectoryAndPicksUpNewFolders() {
        val s = source()
        val snap = snapshot(s)
        s.file("v:M/All/Deep", "d2.mp3")
        s.mtimes["v:M/All/Deep"] = 21
        s.dir("v:M/All/Afro", 40)
        s.file("v:M/All/Afro", "af1.mp3")
        s.mtimes["v:M/All"] = 11
        s.listCalls.set(0)
        val r = SdScanCache.refresh(snap, s)
        assertEquals(2, r.relisted) // All + Deep
        assertEquals(1, r.added) // Afro
        assertEquals(3, s.listCalls.get())
        val names = r.snapshot.allFiles().map { it.second.name }.toSet()
        assertEquals(setOf("a.mp3", "d1.mp3", "d2.mp3", "t1.mp3", "af1.mp3"), names)
    }

    @Test
    fun dropsRemovedFolderAndChildren() {
        val s = source()
        s.dir("v:M/All/Techno/Old", 50)
        s.file("v:M/All/Techno/Old", "o.mp3")
        val snap = snapshot(s)
        assertEquals(4, snap.dirs.size)
        // Xoá Techno (và Old) khỏi thẻ.
        s.dirs["v:M/All"]!!.removeIf { it.id == "v:M/All/Techno" }
        s.dirs.remove("v:M/All/Techno")
        s.dirs.remove("v:M/All/Techno/Old")
        s.mtimes.remove("v:M/All/Techno")
        s.mtimes.remove("v:M/All/Techno/Old")
        s.mtimes["v:M/All"] = 12
        val r = SdScanCache.refresh(snap, s)
        assertEquals(listOf("All", "All/Deep"), r.snapshot.dirs.map { it.path })
        assertEquals(2, r.removed)
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val s = source()
        val snap = snapshot(s)
        val f = tmp.newFile("sd.bin")
        SdScanCache.save(f, snap)
        val back = SdScanCache.load(f, "v:M")
        assertNotNull(back)
        assertEquals(snap, back)
        assertNull(SdScanCache.load(f, "v:Other"))
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(SdScanCache.load(f, "v:M"))
        assertNull(SdScanCache.load(tmp.root.resolve("missing.bin"), "v:M"))
    }

    @Test
    fun handlesLongExtraValues() {
        val s = source()
        val big = "x".repeat(200_000)
        val snap = snapshot(s).copy(extra = mapOf("meta" to big))
        val f = tmp.newFile("big.bin")
        SdScanCache.save(f, snap)
        assertEquals(big, SdScanCache.load(f, "v:M")!!.extra["meta"])
    }
}

class StemDecodeSchedulerTest {
    @Test
    fun idleUsesAllFourStems() {
        val p = StemDecodeScheduler.plan(stems = 4, anyDeckPlaying = false, cpuCores = 8)
        assertEquals(4, p.parallel)
        assertEquals(0L, p.pauseMs)
        assertTrue(p.startAfterReady)
    }

    @Test
    fun playingCapsAtTwoAndPauses() {
        val p = StemDecodeScheduler.plan(4, anyDeckPlaying = true, cpuCores = 8)
        assertEquals(2, p.parallel)
        assertEquals(2L, p.pauseMs)
    }

    @Test
    fun neverThreeForFourStems() {
        // 5 nhân → còn 3 cho stems → phải hạ xuống 2 (3 luồng = 2 đợt, LOG Lab: chậm hơn 2 luồng).
        assertEquals(2, StemDecodeScheduler.plan(4, false, cpuCores = 5).parallel)
        for (cores in 1..16) for (playing in listOf(true, false)) {
            assertTrue(StemDecodeScheduler.plan(4, playing, cores).parallel in setOf(1, 2, 4))
        }
    }

    @Test
    fun smallDevicesAndEdgeCases() {
        assertEquals(1, StemDecodeScheduler.plan(4, false, cpuCores = 2).parallel)
        assertEquals(1, StemDecodeScheduler.plan(1, false, 8).parallel)
        assertEquals(0, StemDecodeScheduler.plan(0, false, 8).parallel)
        assertEquals(3, StemDecodeScheduler.evenSplit(3, 4))
        assertEquals(2, StemDecodeScheduler.evenSplit(4, 3))
        assertEquals(1, StemDecodeScheduler.evenSplit(5, 4))
    }
}
