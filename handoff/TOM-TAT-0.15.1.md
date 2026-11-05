# FLX10 DJ 0.15.1: tóm tắt để làm tiếp trên Mac

Ngày 05/11/2026 (cập nhật sau LOG Lab trên tablet). Viết trên cloud vì repo `lociesprod-bit/Appdj` chưa có mã nguồn app.

**Đã làm ở cloud:**
1. **Module `shared/`.** Toàn bộ mã mới, có test, chép thẳng vào app.
2. **App thử `lab/` (FLX10 Lab).** Dùng để xem trước BROWSE chia cột và đo tốc độ trên thẻ thật.
3. **File này.** Ghi chỗ cần sửa trong app thật. Tên lớp/hàm lấy từ bản dịch ngược APK 0.15.0, chỉ để tham khảo, không commit.

**Cần làm trên Mac:**
- Chép `shared/` vào app.
- Nối vào các lớp cũ theo mục 3.
- Build 0.15.1 (versionCode 47), **ký bằng key thật** `27113161…b84330a7`.
- Viết `GHI-CHU-0.15.1.md`.

---

## 1. Bạn yêu cầu gì (05/11/2026, sau khi thử 0.15.0)

1. Nạp từ thẻ SD còn chậm ở cả 3 chỗ: mở/quét thư viện, LOAD bài, nạp stems.
2. BROWSE chia cột: Track Title | BPM | KEY | MyTag | …
3. Bài cùng key hoặc hợp key với deck MASTER hiện **xanh** như rekordbox.
4. Hàng nút thứ 2 (PERFORM · EXPORT/PREPARE · SONG SONG NGANG · 2/4 DECKS · ĐIỀU KHIỂN 1/2↔3/4 · ẨN MIXER · ẨN ĐIỀU KHIỂN · CDJ/XDJ) chuyển vào ⚙.

## 2. Số đo từ LOG 0.15.0 (`FLX10_20261103-184640_0.15.0.log`)

| Việc | Thời gian | Nguyên nhân (từ LOG + mã dịch ngược) |
|---|---|---|
| LOAD bài đầu tiên sau khi mở app | READY 11 829 ms, **`resolve=10984ms`** | `Library.resolveAudio()` → `SafTree.resolve(path)` gọi `children()` cho từng thư mục trên đường dẫn. `All Music DJ/` có ~12.000 mục (4.440 bài + 3.701 stems + file `._`). Lúc mở app, thư viện nạp từ đệm nên `SafTree.cache` còn trống, và lần LOAD đầu phải liệt kê cả thư mục. |
| LOAD các bài sau | READY 384–974 ms | Ổn (cache của `SafTree` đã có). |
| Mở thư viện có đệm | 8 958–10 528 ms; "4440 mục sẵn sàng" sau ~0,8 s | Thẻ SD xong sau 0,8 s (`SdLibrary.load`). ~9,6 s còn lại là các nguồn quét sau thẻ SD trong `Library.scan`. Thời gian từng nguồn đã có trong `Source.diagnostics` (mục "Chẩn đoán") nhưng chưa ghi ra LOG. |
| Quét lần đầu / ↻ Quét | 28 371–44 319 ms | `SdLibrary.index()` duyệt BFS **tuần tự** bằng `SafTree.children()`. Mỗi `moveToNext()` của cursor còn bọc trong `PerformanceResources.io(...)`. Sau đó mới `RekordboxXml.read` (3 841 bài). |
| Nạp stems | 40 995–79 751 ms (`stemsBackground=79750ms`) | ① Stems chỉ bắt đầu sau khi `MediaLoadEngine.loadForPerformance()` trả về, tức sau khi nạp nền bài gốc xong (~16 s). ② Nếu lúc đó deck đang phát (`!Engine.isPlaying(d)`) thì **bỏ qua** stems; vì vậy LOG có "Deck 1: chưa có stems". ③ `StemLoader.loadScoped` giải mã 4 stem **lần lượt**, mỗi stem ~20 s với bài 4,8 phút. |

## 2b. Số đo FLX10 Lab trên tablet (`LAB_20261105-174950.log`, Xiaomi 2405CRPFDG, thẻ LOCIES SD)

| Việc | Đo được | So sánh |
|---|---|---|
| Resolve 1 bài, cách mới (SAF dựng documentId + 1 query) | TB 3,3–6,0 ms, chậm nhất 10,7–17,9 ms | Cách cũ (liệt kê `All Music DJ`, 11 299 mục): 7 804–8 426 ms |
| ↻ Quét đầy đủ bằng SAF, 3 luồng | 10 255 ms (liệt kê 9 441 · XML 5 MB 225 · ghép 524) | 0.15.0: 28–44 s |
| Liệt kê cả cây `All Music DJ` (189 thư mục, 8 335 mục) | SAF 7 438 ms · **File 595 ms** | File nhanh hơn ~12 lần |
| Giải mã 4 stem, bài 246 s | 1 luồng 25,1 s · 2 luồng 13,6 s · 3 luồng 15,1 s · **4 luồng 10,6 s** | 0.15.0: ~80 s |
| Giải mã 4 stem, bài 173 s | 1 luồng 17,7 s · 2 luồng 9,8 s · 3 luồng 11,2 s · **4 luồng 8,1 s** | |
| Tách `.serato-stems` (XOR → 4 MP3 tạm) | 0,5–0,6 s | |

**Rút ra:**
- **Resolve:** đạt mục tiêu.
- **SAF là nút cổ chai khi quét:** riêng thư mục gốc `All Music DJ` (11 299 mục) đã mất ~8 s qua SAF. Chạy song song không giúp vì đó là một thư mục duy nhất.
- **Đọc thẻ bằng File:** bạn đã đồng ý dùng khi có "Quyền truy cập mọi tệp", không có thì quay về SAF (mục 3.4).
- **Stems:**
  - 3 luồng chậm hơn 2, vì 4 stem chia thành 2 đợt. Dùng **4 luồng khi rảnh, 2 khi đang phát** (mục 3.5).
  - Pipeline app 0.15.0 chậm ~2,7 lần so với giải mã thô: ~20 s/stem cho bài 290 s, trong khi giải mã thô ~6,2 s/stem cho bài 246 s. Cần đo trên Mac (mục 3.5 bước 6).
- **Cột mặc định:** theo lựa chọn của bạn trong Lab, Track Title | BPM | Key | MyTag | Time | Rating | Stems (mục 3.6).
- **Chưa có:** số đo "Mở lại có đệm" và "Mở file cho MediaExtractor". Đã đo ở lab2, xem mục 2c.

## 2c. Số đo lab2 trên tablet (4 LOG `LAB_20261105-1820…1831`)

| Việc | FILE (có quyền truy cập mọi tệp) | SAF | 0.15.0 |
|---|---|---|---|
| ↻ Quét đầy đủ | **3 938 ms** (liệt kê 3 158 · XML 206 · ghép 528) | 8 768 ms (liệt kê 7 606) | 28–44 s |
| Mở lại có đệm: danh sách sẵn sàng | 732 ms | 609 ms | thẻ SD 0,8 s, cả thư viện 9–10,5 s |
| Mở lại có đệm: kiểm tra 189 thư mục | 40 ms | 413 ms (190 query) | — |
| Mở lại có đệm: tổng | **774 ms** | **1 027 ms** | 9–10,5 s |
| Resolve 20 bài | — | TB 2,9 ms (chậm nhất 12,7) | cách cũ 6 101 ms |
| Mở file cho MediaExtractor (content URI) | chưa đo | TB 91 ms, lần đầu 371 ms | `sequentialSourceSetup` ~340 ms |
| 4 stem, bài 218 s (giải mã thô) | — | 1 luồng 22,1 s · 2 luồng 11,7 s · **4 luồng 9,4 s** | ~80 s |

**Kết luận:**
- **Mọi mục tiêu đo được trong Lab đều đạt:**
  - mở thư viện có đệm < 2 s (đạt 0,8–1 s);
  - resolve < 5 ms (đạt ~3 ms);
  - quét File ~4 s, SAF ~9 s;
  - stems 4 luồng ~9–11 s.
- **Thời gian liệt kê bằng File dao động:** lần này mất 3,2 s ("lạnh"), lần trước 0,6 s vì đo ngay sau một lần quét SAF nên đã có cache. Có đệm thì lần mở sau chỉ cần kiểm tra mtime (40 ms), không phải liệt kê lại.
- **Thiếu (tuỳ chọn):** thời gian mở file cho MediaExtractor bằng **đường dẫn File**. Lúc bấm "Đo resolve" chưa có quyền. Khi ghép trên Mac, so `sequentialSourceSetup` trong LOG app giữa content URI và đường dẫn.
- **Lab đã xong việc đo.** Phần còn lại phải làm trên Mac (mục 3 và 7): ghép `shared/` vào app, tìm vì sao pipeline stems chậm ~2,7 lần so với giải mã thô (3.5 bước 6), chạy test native, ký bằng key thật.

## 3. Việc cần nối vào app thật

### 3.1 Resolve tức thì: sửa `resolve=10984ms` (ưu tiên 1, dễ nhất)

- Chép `shared/.../sd/SafDocId.kt`, `SafFastList.kt`, `ParallelWalker.kt`, `SdAccess.kt`.
- Đo trên tablet: TB 3,3–6,0 ms so với 7,8–8,4 s cách cũ.
- Trong `Library.resolveAudio(track)`, trước `reader.resolve(track.getPath())`:
  ```kotlin
  // reader.root là tree URI Music; track.path dạng "All Music DJ/x.wav" (tên đúng như trên thẻ).
  val fast = SafFastList(ctx.contentResolver, reader.root).resolve(track.path)
  if (fast != null && !fast.entry.directory) return fast.uri
  // không được (provider khác, file đổi tên/xoá) → cách cũ
  ```
  Tốt hơn: thêm `SafTree.fastEntry(path)`, dùng `SafDocId.child(rootId, path)` + 1 query trên document URI, để mọi chỗ gọi `reader.resolve(...)` đều nhanh, kể cả resolve stems trong `PerformanceSession.load` (`library.resolve(it4.copy(path = stems))`).
- **Chỉ dùng cho tree của `com.android.externalstorage.documents`** (`SafDocId.isPathShaped`). Provider khác vẫn dùng cách cũ.
- Tên file phải là tên trên thẻ (lấy từ lần liệt kê, đã lưu trong `sd-library-v1/*.bin`), không lấy từ XML. Tên tiếng Việt ghi từ macOS thường là NFD.
- LOG đã có `resolve=…ms`. **Mục tiêu dưới 5 ms.**
- Khi có "Quyền truy cập mọi tệp" (mục 3.4): `SdAccess.resolve(path)` trả cả đường dẫn File, nên chỉ cần 1 lần `stat`. Mở bài bằng `MediaExtractor.setDataSource(path)` thay cho content URI; app 0.15.0 tốn `sequentialSourceSetup` ~340 ms qua content URI. Lab2 đo cả hai.

### 3.2 `SafTree.children()`: hai lỗi làm chậm mọi thứ

1. `PerformanceResources.INSTANCE.io { c.moveToNext() … }` bọc **từng dòng** cursor. Chỉ bọc một lần cho cả `query(...)`, rồi đọc cursor bình thường như `SafFastList.list()`.
2. `synchronized (this.cache)` được giữ **suốt lúc query**, nên một lần liệt kê 10 s khoá mọi thread khác cần `children()`, kể cả thread LOAD. Đổi thành: kiểm tra cache trong lock, query ngoài lock, rồi `put` trong lock. Có thể thêm `ConcurrentHashMap<String, Future<List<Entry>>>` để hai thread không query trùng một thư mục.

### 3.3 Mở thư viện có đệm: ~9,6 s còn lại

- Ghi `Source.diagnostics` của mọi nguồn ra LOG sau mỗi lần quét (`formats; total=… ms` + `reader.diagnostics()`). Hiện chỉ xem được trong "Chẩn đoán". Có số này là biết ngay 9,6 s nằm ở nguồn nào.
- Nhiều khả năng là nguồn "USB · LOCIES SD" (gốc thẻ, 6 thư mục): dò `PIONEER/rekordbox/export.pdb`, `exportLibrary.db`, OneLibrary/Device Library. Nếu gốc thẻ không có `PIONEER`, lưu kết quả "không có export" theo mtime gốc thẻ và bỏ qua ở lần mở sau.
- Đừng chặn danh sách vì việc kiểm tra: `scan$publish` đã báo "4440 mục sẵn sàng" sau 0,8 s, nên browser phải dùng được ngay từ lúc đó (kiểm tra xem `refreshLibraryBrowser` có đợi `scanRunning` không).

### 3.4 ↻ Quét: 28–44 s → ~0,6–1 s với File, ~10 s với SAF

**Bạn đã đồng ý** dùng File khi có "Quyền truy cập mọi tệp". Lab đo: File 595 ms, SAF 7 438 ms.
- **Manifest:** thêm `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage"/>`.
- **⚙:** thêm nút "Đọc thẻ nhanh (cấp quyền truy cập mọi tệp)", mở `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` với `package:` của app. Lab có sẵn `askAllFilesAccess()` làm mẫu. Trong `onResume`, nếu vừa được cấp quyền thì chuyển sang File.
- **Chọn nguồn:** `SdAccess.choose(contentResolver, treeUri)` chọn FILE khi có quyền và `/storage/<volume>/Music` đọc được, ngược lại SAF. Mọi id (`rootId`, `childId`, `DirEntry.id`) thuộc nguồn đang dùng; đường dẫn tương đối `All Music DJ/x.mp3` giống nhau ở cả hai chế độ, nên `SdLibrary.File.path` không phải đổi.
- **`SdLibrary.index(reader)`:** thay vòng BFS tuần tự bằng `ParallelWalker(access.source, 3).walk(access.childId("All Music DJ"), "All Music DJ")`. Mỗi `DirListing.entries` có đủ id/tên/thư mục/size/mtime như `SafTree.Entry`, nên phần lọc `AUDIO`/`STEMS` giữ nguyên.
- **Đệm `sd-library-v1/*.bin`:** stamp phải ghi thêm chế độ (FILE/SAF), vì id khác nhau.
- **Đọc file:** `access.openInputStream(id)` cho XML; `access.setExtractorSource(extractor, ctx, id)` hoặc `Resolved.path` để giải mã.
- **XML:** đọc `RekordboxXml.read` **song song** với việc liệt kê, mỗi việc một thread, rồi ghép ở `SdLibrary.build`.
- **Mất quyền:** người dùng thu hồi quyền hoặc rút thẻ → `SdAccess.choose` tự trả SAF; nhớ báo trong LOG.
- `SdScanCache` (trong `shared/`) có sẵn kiểu đệm "stat từng thư mục, chỉ liệt kê lại thư mục đổi". 0.15.0 hiện chỉ so mtime của `All Music DJ`. Thêm bài vào thư mục con thì không đổi mtime của `All Music DJ`, nên nên dùng `SdScanCache.refresh` để bắt được.

### 3.5 Stems: 40–80 s → mục tiêu dưới 30 s và không bao giờ bị bỏ qua

1. **Bắt đầu sau READY**, không đợi `loadForPerformance()` trả về. Trong `PerformanceSession.load`, khi callback `PerformanceSession$load$2$result$1` báo `wasReady`, gửi job stems sang worker riêng.
   - Cần `result.fingerprint`: có sau `hashAfterReady` (~40 ms).
   - Cần `Engine.length(d)`: số frame đã có ở READY (`frames=12776831` trong dòng READY).
   - Phần kiểm chứng `StemAlignment` đọc cửa sổ audio gốc qua `original` (region decoder), không cần PCM nền đầy đủ.
2. **Bỏ điều kiện `!Engine.isPlaying(d)` ở bước giải mã.** Nếu cần an toàn khi deck đang phát, chỉ áp dụng nó cho bước bật/chuyển sang stems (`stemEndOwned`/commit). Không bỏ việc nạp.
3. **Giải mã song song.** Trong `StemLoader.loadScoped`, vòng `for (stem in prepared.stems) decode(stem, sink)` chạy lần lượt. Đổi thành executor:
   ```kotlin
   val plan = StemDecodeScheduler.plan(stems = 4,
       anyDeckPlaying = Engine.isPlaying(0) || Engine.isPlaying(1) || Engine.isPlaying(2) || Engine.isPlaying(3),
       cpuCores = Runtime.getRuntime().availableProcessors())
   // Rảnh: 4 luồng; đang phát: 2 luồng; không bao giờ 3 (4 stem chia 3 luồng = 2 đợt, Lab đo chậm hơn 2 luồng).
   // Mỗi stem: PcmTrackCache.writer riêng + Engine.stemAppendOwned(deck, owner, type, …); nghỉ plan.pauseMs mỗi 16 mẻ khi đang phát.
   ```
   - Decoder MP3 là phần mềm (`c2.android.mp3.decoder`), nên **stems dùng pool riêng**, không lấy slot trong `codecLimit=3` của `PerformanceResources` (dành cho bài gốc và CUE).
   - Lab đo: 4 luồng 10,6 s, 2 luồng 13,6 s, 1 luồng 25,1 s (bài 246 s).
   **Phải kiểm tra trước:** `Engine.stemAppendOwned` (native) có an toàn khi các type khác nhau append cùng lúc không. Chạy lại test TSan với 4 luồng append. Nếu không an toàn: giải mã song song vào hàng đợi, một luồng append.
4. `PcmTrackCache.replay` đã cho "/4 từ cache", nên lần sau LOAD lại bài đó gần như tức thì. Giữ nguyên.
5. LOG: thời gian tách, giải mã từng stem và số luồng. Lab đã có dòng "4 stem, N luồng" để so.
6. **Pipeline app chậm ~2,7 lần so với giải mã thô.** Giải mã thô ~6,2 s/stem cho bài 246 s; app 0.15.0 ~20 s/stem cho bài 290 s. Ghi `LoadTrace` từng phần của mỗi stem (đọc extractor, chờ codec, chuyển PCM, ghi `PcmTrackCache`, `stemAppendOwned`, chờ admission). Nghi vấn:
   - `Process.setThreadPriority(10)` (BACKGROUND) ở luồng nạp nền: stems nên chạy ở `THREAD_PRIORITY_DEFAULT` khi không deck nào phát;
   - `PerformanceResources.io` bọc từng chunk đọc/ghi;
   - ghi `PcmTrackCache` xuống đĩa đồng bộ trong vòng giải mã: nên tách sang luồng ghi riêng;
   - resample/convert không cần thiết khi stem đã 44,1 kHz.

### 3.6 BROWSE chia cột

- Chép `shared/.../browse/*` (`BrowserColumns`, `BrowserRow`, `TrackRowView`, `BrowserHeaderView`, `BrowserPrefs`, `MyTags`, `CamelotKey`).
- `MainActivity.buildBrowser`: adapter hiện tạo `TextView` với `track.displayName` (chuỗi "Tên\nartist · BPM · key · STEMS" dựng trong constructor `Library.Track`). Thay bằng:
  ```kotlin
  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val v = convertView as? TrackRowView ?: TrackRowView(this@MainActivity)
      val track = browserPages.get(position) ?: return v
      val row = rowFor(track) // cache BrowserRow theo track: getView rất nóng
      v.bind(row, browserColumns, KeyMatch.of(masterKey, row.camelot), position == sel, position % 2 == 1)
      return v
  }
  ```
  `rowFor(track)`:
  - thư mục (`track.directoryId != null`) → `BrowserRow(id, track.name, isFolder = true)`;
  - `fileMode` → title = `track.path`;
  - còn lại → `BrowserRow.fromRekordbox(id, e.title.ifBlank { track.name }, e.artist, e.bpm, e.key, e.myTags, e.comment, e.genre, e.durationMs, e.rating, e.stems.isNotEmpty())`, với `e = track.exported`.
- Đặt `BrowserHeaderView` ngay trên `ListView` (cùng padding, cùng độ rộng). `onSort` → sắp xếp trong `BrowserQueryWorker`/`BrowserPages`, vì dữ liệu chia trang nên không sắp trong adapter. Dùng `BrowserColumns.comparator(col, desc)`; thư mục luôn ở đầu, giá trị trống luôn ở cuối.
- **MyTag:** `RekordboxTrack.myTags` (export.pdb) nếu có. Với thẻ SD/XML, `MyTags.resolve` lấy từ khối `/* … */` trong Comment/Genre (tuỳ chọn "Thêm My Tag vào comment" của rekordbox; XML không có My Tag riêng). Dùng cùng danh sách này cho mục "# MyTag" ở cột trái (`rebuildCategories`) và hộp "MyTag". Khung chi tiết hiện Comment đã bỏ khối tag (`MyTags.split(...).rest`).
- **⚙ → "Cột BROWSE":** checkbox cho từng `ColumnId`, lưu bằng `BrowserPrefs`. **Mặc định (bạn đã chọn): Track Title, BPM, Key, MyTag, Time, Rating, Stems.** Artist tắt, bật lại được.
- Ảnh xem trước (render bằng chính `TrackRowView`, MASTER = 10A): `handoff/browse-preview.png`.

### 3.7 Key hợp với MASTER hiện xanh

- Deck MASTER: `DjController.getMaster()` (0-based, `-1` = không có).
- Key gốc: `metadata[d].keyIndex` (`TrackMetadata.getKeyIndex()`, 0–23 theo `MusicalKey`) → `CamelotKey.fromKeyIndex(idx)`.
- Key đang nghe: `MasterKey.effective(base, tempoRatio, masterTempo = MT đang bật)`. MT tắt thì key dịch theo tempo (+6% ≈ +1 nửa cung).
- Key của từng bài: `CamelotKey.parse(RekordboxTrack.key)`. Đọc được "11A", "Am", "A♭m", "F#", Open Key và cả nhãn "1A · A♭m".
- `KeyMatch.of(master, track)` trả về:
  - `SAME`: cùng key, chữ Key xanh đậm và in đậm;
  - `COMPATIBLE`: cùng chữ và số ±1 (vòng 12↔1), hoặc cùng số và khác chữ A↔B; chữ Key xanh;
  - còn lại: màu thường.
- Gọi `adapter.notifyDataSetChanged()` khi:
  - đổi deck MASTER (các chỗ gán `DjController.master`);
  - LOAD xong trên deck MASTER;
  - sửa key bằng `editKey`/`updateKey(d)`;
  - bật/tắt MT;
  - tempo vượt sang nửa cung khác (so key đang nghe với lần trước trong vòng cập nhật UI, chỉ notify khi đổi).

### 3.8 Hàng nút thứ 2 vào ⚙

- `MainActivity.buildUi`: `val modes = row()`, sau đó lần lượt thêm:
  - `btn("PERFORM")`;
  - `prepareTab = btn("EXPORT / PREPARE")`;
  - nút bố cục (vòng "SONG SONG NGANG" / "SONG SONG DỌC" / "ĐỘC LẬP CDJ");
  - `btn("2 / 4 DECKS")`;
  - `btn("ĐIỀU KHIỂN 1/2 ↔ 3/4")`;
  - nút mixer ("ẨN MIXER"/"HIỆN MIXER");
  - `controlsToggle` ("ẨN ĐIỀU KHIỂN");
  - `displayTab = btn("CDJ / XDJ")`.
- **Không add `modes` vào layout.** Chiều cao đó dành cho waveform.
- `performanceSettings()` (dialog "Thiết lập trình diễn", mở bằng nút ⚙ trên thanh trên): thêm `SettingsToggleSection` ở đầu dialog, gồm 8 `Item` gọi **đúng lambda cũ**.
  - `label` đọc trạng thái hiện tại (ví dụ `if (mixerVisible) "ẨN MIXER" else "HIỆN MIXER"`).
  - `isOn`: null cho nút hành động (PERFORM, EXPORT / PREPARE, CDJ / XDJ); khác null cho nút bật/tắt.
  - Các nút áp dụng ngay, không cần bấm "Lưu".
- Code khác còn cập nhật chữ/màu của `prepareTab`, `controlsToggle`, `displayTab` thì giữ các field đó (không gắn vào layout) hoặc gọi `section.refresh()`.
- MIDI/phím tắt không đổi.

### 3.9 Sóng chi tiết, grid đậm, grid chuẩn rekordbox, SYNC bấm đôi (yêu cầu 05/11 18:42)

Bạn gửi ảnh màn EXPORT / PREPARE (zoom 0,27 s) và ảnh rekordbox. Có 4 việc:

1. **Sóng trông "giả", khó đặt grid.**
   - **Nguyên nhân:** `ScrollWaveView.drawWave` chỉ có ~300 cột/giây (`columnsPerPixel = framesPerPixel * 300 / rate`, tile 512 cột, `WaveEnvelope.peaks`). Ở 0,27 s trên 2000 px còn ~80 cột bị kéo giãn bằng `canvas.scale`, nên thành mảng màu mượt.
   - **Sửa:** chép `shared/.../wave/DetailWave.kt` + `DetailWaveView.kt`.
   - **Dữ liệu:** `DetailWaveBuilder(rate, 16, frames)` nhận PCM ngay trong **lượt giải mã tuần tự** của `ProgressiveTrackLoader` (chỗ có `short[] stereo` trước khi gọi `Engine.deckStreamWrite/deckStreamAppend/deckAppendOwned`): `builder.feed(stereo, frames * 2, 2)`. Không nạp phần cửa sổ CUE ghi trước, vì builder cần thứ tự liên tục. Xong thì giữ `builder.build()` theo deck.
     - Mỗi khối 16 frame lưu min/max có dấu + bass/mid/treble.
     - Bộ nhớ ~4 MB cho bài 5 phút.
     - CPU khoảng 0,3–0,5 s mỗi bài, chạy trong luồng nền.
   - **Vẽ:** trong `ScrollWaveView.drawWave`, khi `columnsPerPixel < 1` (tức hiển thị < ~6,7 s trên 2000 px) và vùng nhìn thấy đã giải mã xong, vẽ bằng thuật toán `DetailWaveView.drawWave`: mỗi pixel gom min/max các khối; zoom sâu hơn 1 khối/pixel thì nội suy. Ngược lại giữ tile cũ.
   - **Màu:** cùng công thức `RgbWaveData.color` (đỏ = bass, xanh lá = mid, xanh dương = treble). Kick đỏ, clap hồng, hi-hat xanh như rekordbox RGB.
   - **Nét:** dày tối thiểu 2,5 dp; đoạn gần im lặng tô xám.
   - **Ảnh xem trước** (render bằng chính `DetailWaveView`): `handoff/wave-preview-0.27s.png`, `handoff/wave-preview-1.2s.png`.
   - **Thử trên tablet:** Lab3, nút "Sóng & grid".

2. **Vạch grid mờ.**
   - **0.15.0** (`ScrollWaveView`): `gridOverlay` alpha 55/255 nét 2 px; `barOverlay` alpha 95 nét 3 px; tick ngắn `grid`/`bar` ở mép. Trên mật độ ~2 chỉ ~1 dp.
   - **Sửa theo `GridStyle`** (trong `DetailWaveView.kt`):
     - beat: trắng alpha 225, **1,6 dp**;
     - ô nhịp: đỏ (255, 64, 64) đặc, **2,8 dp**;
     - mỗi vạch có viền đen alpha 150 rộng thêm 2 dp;
     - tam giác vàng 8 dp trên/dưới mỗi ô nhịp;
     - số ô nhịp khi các ô nhịp cách nhau ≥ 56 dp; số beat 1–4 khi các beat cách nhau ≥ 34 dp.
   - **Vạch phát:** đổi sang **trắng có mũi tên đỏ**, để không lẫn với vạch ô nhịp đỏ khi căn.

3. **Grid từ rekordbox lệch.**
   - **Nguyên nhân 1 (chắc chắn): chỉ dùng mốc đầu.**
     - `RekordboxXml`: chỉ đọc **TEMPO đầu** (BPM 2 số lẻ + Inizio); các mốc sau chỉ dùng để đếm "tempo thay đổi".
     - `RekordboxAnalysis` (PQTZ): chỉ lấy **beat đầu + tempo/100**; lệch > 3 ms thì đánh dấu `variableGrid` và vẫn dùng đoạn đầu.
     - BPM thật kiểu 125,996 bị làm tròn thành 126,00, nên cuối bài 5 phút lệch ~10–12 ms. Test `fitRecoversPreciseBpmFromPqtzBeats` dựng đúng tình huống này.
   - **Sửa nguyên nhân 1:**
     - chép `wave/BeatGrid.kt`;
     - `RekordboxXml`: gom **mọi** `TEMPO` của bài thành `List<BeatGrid.Companion.Tempo>` rồi gọi `BeatGrid.fromTempos(tempos, duration)`;
     - PQTZ: gom **mọi** beat (ms, beat 1–4) rồi gọi `BeatGrid.fromBeats(ms, beatInBar)`;
     - engine native hiện chỉ có `setGrid(deck, gen, bpm, beat0)`: tạm dùng `grid.fitConstant()`. Đây là BPM khớp mọi beat theo bình phương tối thiểu (ví dụ 125,996), nên hết trôi với grid cố định. Bài đổi tempo thật (`maxErrorMs` > ~5) cần API native mới, ví dụ `setGridBeats(deck, gen, double[] beatFrames)`, để quantize/beat jump/loop/SYNC theo đúng từng beat. Phần vẽ grid dùng `BeatGrid.linesIn` cho mọi trường hợp.
   - **Nguyên nhân 2 (cần số đo): lệch cố định theo định dạng.**
     - Bộ giải mã MP3 của Android (`c2.android.mp3.decoder`) và của rekordbox xử lý encoder delay (LAME, thường 576 mẫu) / decoder delay (529 mẫu) khác nhau, có thể lệch tới ~25 ms. LOG 0.15.0 đã gợi ý: stems MP3 lệch bài gốc đúng **529–530 frame**.
     - **Lab3 "Đo lệch grid"** giải mã ~20 bài đủ các định dạng, so grid XML với kick (`GridOffsetEstimator`), rồi in lệch trung vị từng định dạng (MP3/WAV/M4A/FLAC) và độ trôi. Chênh lệch giữa các định dạng là phần cần bù.
     - Sau khi có LOG: thêm `gridOffsetSec(format, mp3Info)` khi dựng grid (`grid.shifted(offset)`), dựa trên `Mp3Gapless.parse` (LAME delay/padding) và khoá `encoder-delay` của MediaExtractor.

4. **SYNC bấm đôi.**
   - **0.15.0:** `DjController.syncPress(d)` bật/tắt khoá `synced[d]` + `Engine.syncTo(d, m, true)`. Khi khoá, `tempoSlider()` bị bỏ qua. Khi tắt, gọi `tempoSlider(d, slider[d])` nên **tempo nhảy về vị trí thanh trượt**.
   - **Sửa** (chép `sync/BeatSync.kt`):
     ```kotlin
     private val taps = Array(4) { SyncTapDetector(400) }
     fun syncPress(d: Int) = when (taps[d].press(SystemClock.uptimeMillis(), synced[d])) {
         LOCK -> /* như cũ */
         UNLOCK_KEEP_TEMPO -> { synced[d] = false; keepCurrentTempo(d) }   // không gọi tempoSlider(d, slider[d])
         INSTANT_MATCH -> { synced[d] = false; instantMatch(d) }           // khớp một lần, rồi để mở
     }
     ```
     - **`instantMatch(d)`:**
       1. Chụp `DeckSnapshot(position, tempoRatio, grid)` của MASTER và deck d.
       2. `BeatSync.instantMatch(master, slave)` cho tỉ lệ tempo + vị trí mới (cùng pha beat; `alignBars = true` nếu muốn cả ô nhịp; có ×2/÷2).
       3. Đặt tempo như `DjController.timing()` (`Engine.setTiming` hoặc `setDeck(d, 5, …)`), rồi `TempoSlider.positionFor(ratio, tempoRange[d])` để dời thanh trên màn hình và đổi dải nếu cần.
       4. Nhảy tới vị trí mới: `Engine.seek(d, seekToSec * rate / length)`. Nếu muốn mượt hơn khi đang phát, có thể nudge tempo vài trăm ms thay vì nhảy.
     - **`keepCurrentTempo(d)`:** đọc tempo đang chạy, dời thanh tới `TempoSlider.positionFor(...)`.
     - **Thanh tempo phần cứng FLX10:** `TempoPickup.arm(slider)` sau `instantMatch`/`keepCurrentTempo`. Trong xử lý MIDI tempo fader, chỉ gọi `tempoSlider()` khi `pickup.onFader(v)` trả true (soft takeover, tempo không nhảy khi vừa chạm thanh).
     - **RESET:** giữ như cũ (`tempoReset`), là cách duy nhất về tempo gốc.
   - **Test:** `SyncTapDetectorTest`, `BeatSyncTest` (khớp pha beat/ô nhịp, ×2/÷2, vị trí thanh, pickup).

## 4. Các file trong repo

```
shared/src/main/kotlin/com/xdj/flx10test/
  sd/SafDocId.kt              dựng documentId/URI không query; filePath() cho java.io.File
  sd/SafFastList.kt           SAF 1 query/thư mục, stat(), resolve(); FileList (java.io.File)
  sd/SdAccess.kt              chọn File (có quyền truy cập mọi tệp) hoặc SAF; resolve/mở file theo nguồn
  sd/ParallelWalker.kt        duyệt cây song song (DirSource: SAF hoặc File)
  sd/SdScanCache.kt           đệm quét: save/load nhị phân, diff mtime, refresh chỉ thư mục đổi
  sd/StemDecodeScheduler.kt   số stem giải mã song song: 4 khi rảnh, 2 khi đang phát, không bao giờ 3
  sd/ParallelStemDecoder.kt   MediaExtractor+MediaCodec nhiều luồng (Lab dùng để đo)
  browse/CamelotKey.kt        CamelotKey, KeyMatch, MasterKey
  browse/MyTags.kt            tách My Tag từ comment "/* … */"
  browse/BrowserColumns.kt    BrowserRow, ColumnId, SortState, định dạng/sắp xếp/chia độ rộng cột
  browse/TrackRowView.kt      TrackRowView, BrowserHeaderView, BrowserStyle (màu xanh key)
  browse/BrowserPrefs.kt      lưu cột + sắp xếp
  ui/SettingsToggleSection.kt mục "Chế độ & bố cục" trong ⚙
  wave/BeatGrid.kt            grid theo từng beat (mọi TEMPO XML / mọi beat PQTZ), fitConstant cho native, shifted
  wave/DetailWave.kt          dữ liệu sóng chi tiết 16 frame/khối (min/max có dấu + bass/mid/treble), builder nạp dần
  wave/DetailWaveView.kt      vẽ sóng thật khi zoom sâu + GridStyle đậm + vạch phát trắng; so sánh grid cũ
  wave/GridOffsetEstimator.kt đo lệch grid so với kick (lệch cố định, độ trôi); Mp3Gapless đọc LAME delay/padding
  sync/BeatSync.kt            SyncTapDetector (bấm đôi), BeatSync.instantMatch, TempoSlider, TempoPickup
lab/                          FLX10 Lab (applicationId com.xdj.flx10test.lab)
handoff/                      file này + browse-preview.png + wave-preview-*.png
```

- Package con (`.sd`, `.browse`, `.ui`) không trùng tên lớp nào của 0.15.0.
- Chỉ dùng framework Android, không thêm AndroidX.
- Cùng AGP 8.5.2, Kotlin 1.9.24, minSdk 29, compileSdk 34 như app.

## 5. Test đã chạy (cloud, Linux)

- **`./gradlew :shared:testDebugUnitTest :lab:testDebugUnitTest`:** 77 + 10 test **PASS**, gồm cả Robolectric SDK 34. Các nhóm:
  - `CamelotKey`/`KeyMatch`: parse mọi dạng key, vòng 12↔1, A↔B, transpose, tempo → nửa cung;
  - `MyTags`: tag tiếng Việt, khối nằm giữa comment, trùng tag;
  - `SafDocId`: tên có dấu cách, `#`, `%`, NFD;
  - `SdAccess`: không có quyền / thẻ không đọc được thì quay về SAF; chế độ File resolve, mở và liệt kê theo đường dẫn, kể cả tên tiếng Việt;
  - `ParallelWalker`: chạy song song thật, bỏ file `._`, báo lỗi;
  - `SdScanCache`: không đổi thì chỉ stat; đổi thì liệt kê lại đúng thư mục; xoá thư mục; lưu/đọc;
  - `StemDecodeScheduler`: chỉ 1/2/4 luồng, không bao giờ 3;
  - `BrowserColumns`: sắp xếp, định dạng, chia cột, cột mặc định mới;
  - Views: `TrackRowView`, `BrowserHeaderView`, `SettingsToggleSection`, `BrowserPrefs`, cắt chữ "…";
  - `BeatGrid`: mọi mốc TEMPO, Battito, đổi tempo, PQTZ 125,996 BPM so với 126,00, bù lệch;
  - `GridOffsetEstimator`: bài giả kick 55 Hz, cài lệch 12 ms thì đo ra 12,0 ms (IQR 0,6); phát hiện trôi khi BPM làm tròn;
  - `Mp3Gapless`: LAME delay/padding, bỏ qua ID3;
  - `DetailWaveBuilder`/`DetailWaveView`: giữ hình sóng có dấu, màu theo dải, vẽ ở zoom 0,27 s và 4 s;
  - SYNC: bấm đôi, khớp pha beat/ô nhịp, ×2/÷2, vị trí thanh, soft takeover;
  - Lab: đọc rekordbox XML (kể cả nhiều mốc TEMPO), tách `.serato-stems`, khởi động Activity.
- **Lưu ý:** test chạy với `LC_ALL=C.UTF-8` (đã đặt trong `build.gradle.kts`). Locale POSIX làm JVM đổi tên file tiếng Việt thành "?".
- **`:shared:lintDebug`, `:lab:lintRelease`:** 0 lỗi, 0 cảnh báo.
- **APK Lab:** `apksigner` đạt (v2), `aapt2` (`com.xdj.flx10test.lab` 3/0.15.1-lab3, target 34), `zipalign -c -p 4` đạt.
- **Chưa làm được ở cloud:**
  - test native ASan/UBSan/TSan;
  - Robolectric SDK 29;
  - chạy FLX10 DJ thật.

## 6. FLX10 Lab: thử trên tablet trước khi ghép

- **Cài đặt:** cài `FLX10-Lab-0.15.1-lab3.apk`. Lab có tên gói riêng nên **không đụng FLX10 DJ**. Các bản Lab ký cùng key tạm nên cài đè được.
- **Key ký:** key tạm (`CN=FLX10 Lab (key tam thoi)`, SHA-256 `e2d1bac1…d697cf34`) chỉ có trên máy cloud, không commit vì repo public. Build lại Lab trên Mac thì ký bằng key khác, nên cần gỡ Lab cũ trước khi cài.

**Các bước (từ lab2):**
1. Mở Lab → **Chọn thư mục Music** (cùng thư mục như FLX10 DJ).
2. **Nguồn: SAF/FILE:** bấm để chuyển. Chuyển sang FILE thì Lab mở màn hình cấp "Quyền truy cập mọi tệp"; cấp xong quay lại là Lab tự chuyển. Mỗi nguồn có đệm riêng.
3. **↻ Quét:** đo quét đầy đủ bằng nguồn đang chọn.
4. **Thoát hẳn Lab (vuốt khỏi đa nhiệm), mở lại:** Lab tự **mở từ đệm**, ghi dòng "Mở lại có đệm".
5. **Đo resolve:** đo nguồn đang dùng, cách cũ 0.15.0 (chỉ lần đầu mỗi phiên vì mất ~8 s), và **mở file cho MediaExtractor** (content URI so với đường dẫn File).
6. Chọn một bài có STEMS → **Đo stems**: tách + giải mã 4 stem với 1/2/4 luồng.
7. (Tuỳ chọn) **SAF vs File**.
8. **Thử browser:**
   - giữ lâu một bài để đặt làm MASTER, hoặc bấm **MASTER** để chọn key, tempo % và MT;
   - **Chỉ bài hợp key** lọc danh sách;
   - **⚙ Cột** bật/tắt cột;
   - bấm tiêu đề cột để sắp xếp.
9. LOG nằm ở `Music/LOG/LAB_<thời gian>.log`, mỗi lần mở Lab một file. Gửi file mới nhất (đính kèm vào chat là được, không cần Mac).

**Lab3: Sóng & grid**
- Chọn một bài có BPM (từ XML) → **Sóng & grid**:
  - sóng chi tiết thật + grid đậm từ mọi mốc TEMPO, so với grid cũ 0.15.0 (vạch xanh ngọc mảnh);
  - **◀ Beat / Beat ▶**, **± Zoom**, **0,27 s**, **Đầu bài / Giữa / Cuối**;
  - **Bù ±1/±10 ms** để thử dời grid; **Bù theo đo** dùng số đo tự động;
  - kéo ngang để tua, chụm hai ngón để zoom.
- **Đo lệch grid** (màn chính): ~20 bài nhiều định dạng → LOG từng bài và tổng kết theo định dạng.

**Đọc LOG Lab:**
- **"Liệt kê All Music DJ bằng FILE/SAF: … ms":** so với 28–44 s của 0.15.0.
- **"Mở lại có đệm (…): … danh sách sẵn sàng sau … ms · kiểm tra … thư mục … ms":** phần kiểm tra chạy nền.
- **"Đo resolve … · cũ 0.15.0 (…): … ms":** cách cũ chính là `resolve=10984ms`.
- **"Mở file cho MediaExtractor … content URI: … · đường dẫn File: …":** nếu File nhanh hơn rõ (0.15.0 có `sequentialSourceSetup` ~340 ms), app nên mở bài bằng đường dẫn khi có quyền.
- **"4 stem, N luồng: … ms":** dòng có "← app dùng …" là số luồng `StemDecodeScheduler.plan()` chọn.
- **"bài gốc: … ms":** tốc độ giải mã thô của máy. So với `decodeTotal` trong LOG app để biết pipeline app tốn thêm bao nhiêu.
- **Kiểm tra mtime thư mục khi thêm bài từ Mac:** thêm 1 bài vào `All Music DJ/<thư mục con>`, cắm lại thẻ, mở Lab. Nếu dòng "Mở lại có đệm" ghi "liệt kê lại 1" thì đệm theo mtime dùng được trên exFAT.

## 7. Phát hành 0.15.1 (trên Mac)

- versionCode **47**, versionName **0.15.1**. Ký bằng key thật (cùng key 0.14.10/0.15.0) để cài đè.
- Viết `GHI-CHU-0.15.1.md` theo mẫu 0.15.0 (Cài đặt · Có gì mới · Cần thử trên tablet · Kiểm tra đã chạy). Có gì mới:
  - thẻ SD nhanh hơn (resolve, mở đệm, quét song song); có nút cấp "Quyền truy cập mọi tệp" để đọc thẻ bằng File, nhanh hơn ~12 lần;
  - stems bắt đầu ngay sau READY, giải mã song song (4 luồng khi rảnh, 2 khi đang phát), không bị bỏ khi deck đang phát;
  - BROWSE chia cột (mặc định Track Title | BPM | Key | MyTag | Time | Rating | Stems), sắp xếp theo cột, chọn cột trong ⚙;
  - MyTag lấy từ comment;
  - cột Key xanh theo deck MASTER;
  - hàng nút thứ 2 chuyển vào ⚙.
- Chạy lại toàn bộ test:
  - native ASan/UBSan + TSan, thêm ca 4 luồng `stemAppendOwned`;
  - core Kotlin;
  - Robolectric SDK34 + SDK29;
  - lint;
  - `apksigner`/`aapt2`/`zipalign`;
  - 46 bài stems mẫu vẫn phải 46/46 khớp.
- Thêm vào "Cần thử trên tablet":
  - LOG có `resolve` < 5 ms ở lần LOAD đầu sau khi mở app;
  - "Thư viện: 4440 mục" < 2 s khi có đệm;
  - "stems đã nạp sau" < 30 s với bài 5 phút;
  - cột Key xanh đổi theo deck MASTER;
  - 8 nút nằm trong ⚙.
