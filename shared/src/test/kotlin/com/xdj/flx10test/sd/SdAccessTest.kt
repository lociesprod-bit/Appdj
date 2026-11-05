package com.xdj.flx10test.sd

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SdAccessTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app: Application = RuntimeEnvironment.getApplication()
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/3661-3634%3AMusic")

    @Test
    fun fallsBackToSafWithoutPermission() {
        val a = SdAccess.choose(app.contentResolver, tree, allFilesAccess = false)
        assertEquals(SdAccess.Mode.SAF, a.mode)
        assertEquals("3661-3634:Music", a.rootId)
        assertEquals("3661-3634:Music/All Music DJ/a.mp3", a.childId("All Music DJ/a.mp3"))
    }

    @Test
    fun fallsBackToSafWhenCardPathUnreadable() {
        // /storage/3661-3634/Music không tồn tại trong môi trường test → phải quay về SAF dù có quyền.
        assertEquals(SdAccess.Mode.SAF, SdAccess.choose(app.contentResolver, tree, allFilesAccess = true).mode)
        assertEquals(SdAccess.Mode.SAF, SdAccess.choose(app.contentResolver, tree, allFilesAccess = true, preferSaf = true).mode)
    }

    @Test
    fun fileModeResolvesAndListsByPath() {
        val root = tmp.newFolder("Music")
        val music = root.resolve("All Music DJ").apply { mkdirs() }
        music.resolve("Bên Trên Tầng Lầu.mp3").writeBytes(ByteArray(42))
        music.resolve("._Bên Trên Tầng Lầu.mp3").writeBytes(ByteArray(1))
        music.resolve("Deep").mkdirs()
        val a = SdAccess.fileMode(SafFastList(app.contentResolver, tree), root.absolutePath)
        assertEquals(root.absolutePath, a.rootId)
        val r = a.resolve("All Music DJ/Bên Trên Tầng Lầu.mp3")
        assertNotNull(r)
        assertEquals(42L, r!!.entry.size)
        assertEquals(music.resolve("Bên Trên Tầng Lầu.mp3").absolutePath, r.path)
        assertEquals("3661-3634:Music/All Music DJ/Bên Trên Tầng Lầu.mp3", r.uri?.let { android.provider.DocumentsContract.getDocumentId(it) })
        assertNull(a.resolve("All Music DJ/missing.mp3"))
        assertNull(a.resolve("All Music DJ/Deep")) // thư mục không phải file
        assertNull(a.childId("../etc/passwd"))
        assertEquals(42, a.openInputStream(r.path!!).use { it.readBytes().size })
        val (dirs, _) = ParallelWalker(a.source, 2).walk(a.childId("All Music DJ")!!, "All Music DJ")
        assertEquals(listOf("All Music DJ", "All Music DJ/Deep"), dirs.map { it.path })
        assertEquals(listOf("Bên Trên Tầng Lầu.mp3"), dirs[0].entries.filter { !it.directory }.map { it.name })
    }
}
