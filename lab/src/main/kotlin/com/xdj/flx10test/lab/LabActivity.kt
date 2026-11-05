package com.xdj.flx10test.lab

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.xdj.flx10test.browse.BrowserColumns
import com.xdj.flx10test.browse.BrowserHeaderView
import com.xdj.flx10test.browse.BrowserPrefs
import com.xdj.flx10test.browse.CamelotKey
import com.xdj.flx10test.browse.ColumnId
import com.xdj.flx10test.browse.KeyMatch
import com.xdj.flx10test.browse.MasterKey
import com.xdj.flx10test.browse.SortState
import com.xdj.flx10test.browse.TrackRowView
import com.xdj.flx10test.sd.SafDocId
import com.xdj.flx10test.sd.SafFastList
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * FLX10 Lab 0.15.1: thử BROWSE chia cột, tô xanh key hợp với MASTER, và đo tốc độ đọc thẻ SD.
 * Không phát nhạc. Cài song song với FLX10 DJ (applicationId riêng).
 */
class LabActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lab-worker") }

    private lateinit var prefs: SharedPreferences
    private lateinit var browserPrefs: BrowserPrefs
    private lateinit var log: LabLog

    private var list: SafFastList? = null
    private var library: LabLibrary? = null
    private var bench: Bench? = null

    private var all: List<LabTrack> = emptyList()
    private var haystack: List<String> = emptyList()
    private var visible: List<LabTrack> = emptyList()
    private var selected: LabTrack? = null
    private var columns: List<ColumnId> = ColumnId.DEFAULT
    private var sort: SortState = SortState()
    private var onlyCompatible = false
    @Volatile private var busy = false

    // MASTER giả lập: key gốc + tempo + MT.
    private var masterBase: CamelotKey? = null
    private var masterLabel: String = ""
    private var masterTempoPct = 0.0
    private var masterTempoLock = true
    private val masterKey: CamelotKey?
        get() = MasterKey.effective(masterBase, 1.0 + masterTempoPct / 100.0, masterTempoLock)

    private lateinit var status: TextView
    private lateinit var header: BrowserHeaderView
    private lateinit var listView: ListView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var masterButton: TextView
    private lateinit var filterButton: TextView
    private val actionButtons = ArrayList<TextView>()
    private val logLines = ArrayDeque<String>()

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): LabTrack = visible[position]
        override fun getItemId(position: Int): Long = visible[position].row.id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? TrackRowView ?: TrackRowView(this@LabActivity)
            val t = visible[position]
            v.bind(t.row, columns, KeyMatch.of(masterKey, t.row.camelot), t === selected, position % 2 == 1)
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lab", MODE_PRIVATE)
        browserPrefs = BrowserPrefs(prefs)
        columns = browserPrefs.columns
        sort = browserPrefs.sort
        log = LabLog { line -> ui.post { appendLog(line) } }
        setContentView(buildUi())
        header.columns = columns
        header.sort = sort
        logDeviceInfo()
        restoreTree()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        background = GradientDrawable().apply {
            cornerRadius = dp(6f).toFloat()
            setColor(Color.rgb(42, 45, 51))
        }
        isClickable = true
        setOnClickListener { action() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 19, 23))
            setPadding(dp(8f), dp(8f), dp(8f), dp(4f))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(v: TextView, action: Boolean = true) {
            bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
            if (action) actionButtons.add(v)
        }
        add(button("Chọn thư mục Music") { pickTree() })
        add(button("↻ Quét") { runAction("Quét thẻ") { fullScan() } })
        add(button("Mở lại (đệm)") { runAction("Mở lại có đệm") { openCached() } })
        add(button("⚙ Cột") { showColumnsDialog() }, action = false)
        masterButton = button("MASTER: —") { showMasterDialog() }
        add(masterButton, action = false)
        filterButton = button("Chỉ bài hợp key: TẮT") { toggleFilter() }
        add(filterButton, action = false)
        add(button("Đo resolve") { runAction("Đo resolve") { benchResolve() } })
        add(button("Đo stems") { runAction("Đo stems") { benchStems() } })
        add(button("SAF vs File") { safVsFile() })
        add(button("Ghi LOG") { runAction("Ghi LOG") { } })
        root.addView(HorizontalScrollView(this).apply { addView(bar) })

        status = TextView(this).apply {
            setTextColor(Color.rgb(140, 220, 140))
            textSize = 13f
            maxLines = 2
            setPadding(dp(2f), dp(6f), dp(2f), dp(4f))
            text = "Chọn thư mục Music trên thẻ SD (chứa All Music DJ và XML)."
        }
        root.addView(status)

        val search = EditText(this).apply {
            hint = "Tìm bài, artist, key, MyTag…"
            setHintTextColor(Color.rgb(120, 125, 135))
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim().lowercase()
                    applyFilter()
                }
            })
        }
        root.addView(search)

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val browser = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = BrowserHeaderView(this).apply {
            onSort = { s ->
                sort = s
                browserPrefs.sort = s
                applyFilter()
            }
        }
        browser.addView(header)
        listView = ListView(this).apply {
            adapter = this@LabActivity.adapter
            divider = null
            isFastScrollEnabled = true
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ -> select(visible[position]) }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                val t = visible[position]
                select(t)
                setMasterFromTrack(t)
                true
            }
        }
        browser.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(browser, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))

        logView = TextView(this).apply {
            setTextColor(Color.rgb(120, 230, 120))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(dp(8f), 0, dp(4f), 0)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        content.addView(logScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private var query = ""

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 400) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(value: Boolean, label: String = "") {
        busy = value
        actionButtons.forEach {
            it.isEnabled = !value
            it.alpha = if (value) 0.45f else 1f
        }
        if (value) status.text = "$label…"
    }

    /** Chạy một thao tác trên luồng nền; ghi LOG ra thẻ khi xong. */
    private fun runAction(label: String, block: () -> Unit) {
        if (busy) return
        if (list == null && label != "Ghi LOG") {
            status.text = "Chưa chọn thư mục Music."
            return
        }
        setBusy(true, label)
        worker.execute {
            val t0 = System.nanoTime()
            try {
                block()
            } catch (e: InterruptedException) {
                return@execute
            } catch (e: Exception) {
                log.line("⚠ $label: ${e.message ?: e.javaClass.simpleName}")
            }
            val err = log.flush(contentResolver, list)
            val ms = (System.nanoTime() - t0) / 1_000_000
            ui.post {
                setBusy(false)
                status.text = "$label xong sau $ms ms · ${visible.size}/${all.size} bài" +
                    (err?.let { " · ⚠ LOG: $it" } ?: " · LOG: Music/LOG/${log.fileName}")
            }
        }
    }

    // ---------------------------------------------------------------- Thư mục Music

    private fun pickTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        startActivityForResult(intent, REQ_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            log.line("⚠ Không giữ được quyền thư mục: ${e.message}")
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        attach(uri)
        runAction("Mở thư viện") { if (!openCached()) fullScan() }
    }

    private fun restoreTree() {
        val saved = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return
        val granted = contentResolver.persistedUriPermissions.any { it.uri == saved && it.isReadPermission }
        if (!granted) {
            log.line("Quyền thư mục cũ đã mất: chọn lại thư mục Music")
            return
        }
        attach(saved)
        runAction("Mở thư viện") { if (!openCached()) log.line("Chưa có đệm: bấm ↻ Quét") }
    }

    private fun attach(tree: Uri) {
        val l = SafFastList(contentResolver, tree)
        list = l
        library = LabLibrary(this, l, log)
        bench = Bench(this, l, log)
        log.line("Thư mục Music: ${l.treeDocumentId} · provider ${tree.authority} · documentId dạng đường dẫn: ${if (l.pathShaped) "có" else "không"}")
    }

    // ---------------------------------------------------------------- Thao tác

    private fun fullScan() {
        val lib = library ?: return
        lib.scan()
        publish(lib)
    }

    private fun openCached(): Boolean {
        val lib = library ?: return false
        val done = lib.openCached { ready ->
            val tracks = lib.tracks
            ui.post {
                setTracks(tracks)
                status.text = "Danh sách sẵn sàng sau ${ready.millis} ms (${ready.tracks} bài) · đang kiểm tra thẻ…"
            }
        } ?: return false
        publish(lib)
        return done.tracks >= 0
    }

    private fun publish(lib: LabLibrary) {
        val tracks = lib.tracks
        ui.post { setTracks(tracks) }
    }

    private fun benchResolve() {
        val b = bench ?: return
        val pool = all
        if (pool.isEmpty()) {
            log.line("Đo resolve: chưa có bài, bấm ↻ Quét trước")
            return
        }
        val step = (pool.size / 20).coerceAtLeast(1)
        val sample = (listOfNotNull(selected) + pool.filterIndexed { i, _ -> i % step == 0 }).distinct().take(20)
        b.resolve(sample)
    }

    private fun benchStems() {
        val b = bench ?: return
        val t = selected?.takeIf { it.stemsId != null } ?: all.firstOrNull { it.stemsId != null }
        if (t == null) {
            log.line("Đo stems: không có bài nào có stems")
            return
        }
        if (selected?.stemsId == null) log.line("Đo stems: bài đang chọn không có stems, dùng '${t.row.title}'")
        b.stems(t)
    }

    private fun safVsFile() {
        val b = bench ?: run {
            status.text = "Chưa chọn thư mục Music."
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status.text = "SAF vs File cần Android 11 trở lên."
            return
        }
        if (!b.hasAllFilesAccess()) {
            AlertDialog.Builder(this)
                .setTitle("SAF vs File")
                .setMessage(
                    "Để so tốc độ, Lab cần \"Quyền truy cập mọi tệp\" (chỉ dùng để đọc thẻ, không ghi). " +
                        "Bật cho FLX10 Lab rồi quay lại bấm SAF vs File.",
                )
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Đóng", null)
                .show()
            return
        }
        runAction("SAF vs File") {
            val l = list ?: return@runAction
            val music = (if (l.pathShaped) SafDocId.child(l.treeDocumentId, LabLibrary.MUSIC) else null)
                ?: l.list(l.treeDocumentId).first { it.directory && it.name == LabLibrary.MUSIC }.id
            b.safVsFile(music)
        }
    }

    // ---------------------------------------------------------------- Danh sách

    private fun setTracks(tracks: List<LabTrack>) {
        all = tracks
        haystack = tracks.map { t ->
            val r = t.row
            (r.title + " " + r.artist + " " + (r.camelot?.label ?: r.key) + " " + r.myTags.joinToString(" ") + " " + r.genre).lowercase()
        }
        if (selected != null && selected !in tracks) selected = tracks.firstOrNull { it.relPath == selected?.relPath }
        applyFilter()
    }

    private fun applyFilter() {
        val master = masterKey
        val out = ArrayList<LabTrack>(all.size)
        all.forEachIndexed { i, t ->
            if (query.isNotEmpty() && !haystack[i].contains(query)) return@forEachIndexed
            if (onlyCompatible && master != null && KeyMatch.of(master, t.row.camelot) == KeyMatch.NONE) return@forEachIndexed
            out.add(t)
        }
        val col = sort.column
        visible = if (col == null) out else {
            val cmp = BrowserColumns.comparator(col, sort.descending)
            out.sortedWith { a, b -> cmp.compare(a.row, b.row) }
        }
        adapter.notifyDataSetChanged()
        if (!busy) status.text = "${visible.size}/${all.size} bài" + (library?.xmlLabel?.takeIf { it.isNotEmpty() }?.let { " · XML $it" } ?: "")
    }

    private fun select(t: LabTrack) {
        selected = t
        adapter.notifyDataSetChanged()
        val r = t.row
        status.text = "${r.title} · ${BrowserColumns.formatBpm(r.bpm)} BPM · ${r.camelot?.label ?: "—"} · ${t.relPath}" +
            (t.stemsName?.let { " · stems: $it" } ?: " · không có stems") + " · giữ lâu để đặt làm MASTER"
    }

    private fun toggleFilter() {
        onlyCompatible = !onlyCompatible
        filterButton.text = "Chỉ bài hợp key: ${if (onlyCompatible) "BẬT" else "TẮT"}"
        applyFilter()
    }

    private fun updateMasterButton() {
        val k = masterKey
        masterButton.text = if (k == null) "MASTER: —" else "MASTER: ${k.label} · ${k.noteName}"
        log.line(
            if (k == null) "MASTER: tắt" else "MASTER: $masterLabel · key gốc ${masterBase?.label} · tempo ${"%+.2f".format(masterTempoPct)}% · " +
                "MT ${if (masterTempoLock) "bật" else "tắt"} → key hiện tại ${k.label}",
        )
        applyFilter()
    }

    private fun setMasterFromTrack(t: LabTrack) {
        val k = t.row.camelot
        if (k == null) {
            status.text = "Bài này chưa có key"
            return
        }
        masterBase = k
        masterLabel = t.row.title
        updateMasterButton()
    }

    private fun showMasterDialog() {
        val keys = listOf<CamelotKey?>(null) + (1..12).flatMap { n -> listOf(CamelotKey(n, true), CamelotKey(n, false)) }
        val labels = keys.map { k -> k?.let { "${it.label} · ${it.noteName}" } ?: "— (không có MASTER)" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@LabActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(keys.indexOf(masterBase).coerceAtLeast(0))
        }
        box.addView(spinner)
        val fromSelected = button("Lấy key bài đang chọn") {
            selected?.row?.camelot?.let { spinner.setSelection(keys.indexOf(it)) }
        }
        box.addView(fromSelected)
        val tempo = EditText(this).apply {
            hint = "Tempo % (ví dụ 0.79 hoặc -3)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(if (masterTempoPct == 0.0) "" else masterTempoPct.toString())
        }
        box.addView(tempo)
        val mt = CheckBox(this).apply {
            text = "MT (Master Tempo): giữ key khi đổi tempo"
            isChecked = masterTempoLock
        }
        box.addView(mt)
        AlertDialog.Builder(this)
            .setTitle("Deck MASTER giả lập")
            .setView(box)
            .setPositiveButton("Đặt") { _, _ ->
                val k = keys[spinner.selectedItemPosition]
                masterBase = k
                masterLabel = if (k != null && selected?.row?.camelot == k) selected!!.row.title else "chọn tay"
                masterTempoPct = tempo.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                masterTempoLock = mt.isChecked
                updateMasterButton()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun showColumnsDialog() {
        val cols = ColumnId.entries
        val checked = BooleanArray(cols.size) { cols[it] in columns }
        AlertDialog.Builder(this)
            .setTitle("Cột BROWSE")
            .setMultiChoiceItems(cols.map { it.label }.toTypedArray(), checked) { dialog, which, isChecked ->
                if (cols[which] == ColumnId.TITLE && !isChecked) {
                    (dialog as AlertDialog).listView.setItemChecked(which, true)
                    return@setMultiChoiceItems
                }
                columns = browserPrefs.toggle(cols[which], isChecked)
                header.columns = columns
                adapter.notifyDataSetChanged()
            }
            .setPositiveButton("Xong") { _, _ -> log.line("Cột BROWSE: " + columns.joinToString(" | ") { it.label }) }
            .show()
    }

    private fun logDeviceInfo() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        log.line("FLX10 Lab $version · thử cho FLX10 DJ 0.15.1")
        log.line(
            "Thiết bị: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                "${Runtime.getRuntime().availableProcessors()} nhân · RAM ${mem.totalMem / (1 shl 20)} MB",
        )
    }

    companion object {
        private const val REQ_TREE = 41
        private const val KEY_TREE = "tree"
    }
}
