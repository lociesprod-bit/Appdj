# FLX10 DJ 0.15.1: tóm tắt để làm tiếp trên Mac

Ngày 05/11/2026. Viết trên cloud vì repo `lociesprod-bit/Appdj` chưa có mã nguồn app.

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

## 3. Việc cần nối vào app thật

### 3.1 Resolve tức thì: sửa `resolve=10984ms` (ưu tiên 1, dễ nhất)

- Chép `shared/.../sd/SafDocId.kt`, `SafFastList.kt`, `ParallelWalker.kt`.
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

### 3.2 `SafTree.children()`: hai lỗi làm chậm mọi thứ

1. `PerformanceResources.INSTANCE.io { c.moveToNext() … }` bọc **từng dòng** cursor. Chỉ bọc một lần cho cả `query(...)`, rồi đọc cursor bình thường như `SafFastList.list()`.
2. `synchronized (this.cache)` được giữ **suốt lúc query**, nên một lần liệt kê 10 s khoá mọi thread khác cần `children()`, kể cả thread LOAD. Đổi thành: kiểm tra cache trong lock, query ngoài lock, rồi `put` trong lock. Có thể thêm `ConcurrentHashMap<String, Future<List<Entry>>>` để hai thread không query trùng một thư mục.

### 3.3 Mở thư viện có đệm: ~9,6 s còn lại

- Ghi `Source.diagnostics` của mọi nguồn ra LOG sau mỗi lần quét (`formats; total=… ms` + `reader.diagnostics()`). Hiện chỉ xem được trong "Chẩn đoán". Có số này là biết ngay 9,6 s nằm ở nguồn nào.
- Nhiều khả năng là nguồn "USB · LOCIES SD" (gốc thẻ, 6 thư mục): dò `PIONEER/rekordbox/export.pdb`, `exportLibrary.db`, OneLibrary/Device Library. Nếu gốc thẻ không có `PIONEER`, lưu kết quả "không có export" theo mtime gốc thẻ và bỏ qua ở lần mở sau.
- Đừng chặn danh sách vì việc kiểm tra: `scan$publish` đã báo "4440 mục sẵn sàng" sau 0,8 s, nên browser phải dùng được ngay từ lúc đó (kiểm tra xem `refreshLibraryBrowser` có đợi `scanRunning` không).

### 3.4 ↻ Quét: 28–44 s → mục tiêu dưới 10 s

- `SdLibrary.index(reader)`: thay vòng BFS tuần tự bằng `ParallelWalker(SafFastList(...), 3).walk(musicId, "All Music DJ")`. Mỗi `DirListing.entries` có đủ id/tên/thư mục/size/mtime như `SafTree.Entry`, nên phần lọc `AUDIO`/`STEMS` giữ nguyên.
- Đọc `RekordboxXml.read` **song song** với việc liệt kê, mỗi việc một thread, rồi ghép ở `SdLibrary.build`.
- (Tuỳ số đo Lab) "Quyền truy cập mọi tệp" + `java.io.File` thường nhanh hơn SAF nhiều lần. Chỉ làm nếu dòng `SAF vs File` trong LOG Lab cho thấy đáng. App chỉ cài ngoài (không qua Play) nên xin quyền này được, nhưng giữ SAF làm đường dự phòng.
- `SdScanCache` (trong `shared/`) có sẵn kiểu đệm "stat từng thư mục, chỉ liệt kê lại thư mục đổi". 0.15.0 hiện chỉ so mtime của `All Music DJ`. Thêm bài vào thư mục con thì không đổi mtime của `All Music DJ`, nên nên dùng `SdScanCache.refresh` để bắt được.

### 3.5 Stems: 40–80 s → mục tiêu dưới 30 s và không bao giờ bị bỏ qua

1. **Bắt đầu sau READY**, không đợi `loadForPerformance()` trả về. Trong `PerformanceSession.load`, khi callback `PerformanceSession$load$2$result$1` báo `wasReady`, gửi job stems sang worker riêng.
   - Cần `result.fingerprint`: có sau `hashAfterReady` (~40 ms).
   - Cần `Engine.length(d)`: số frame đã có ở READY (`frames=12776831` trong dòng READY).
   - Phần kiểm chứng `StemAlignment` đọc cửa sổ audio gốc qua `original` (region decoder), không cần PCM nền đầy đủ.
2. **Bỏ điều kiện `!Engine.isPlaying(d)` ở bước giải mã.** Nếu cần an toàn khi deck đang phát, chỉ áp dụng nó cho bước bật/chuyển sang stems (`stemEndOwned`/commit). Không bỏ việc nạp.
3. **Giải mã song song.** Trong `StemLoader.loadScoped`, vòng `for (stem in prepared.stems) decode(stem, sink)` chạy lần lượt. Đổi thành executor:
   ```kotlin
   val plan = StemDecodeScheduler.plan(stems = 4, codecLimit = 3, codecsInUse = <đang dùng>, cueReserve = 1,
                                       anyDeckPlaying = Engine.isPlaying(0) || Engine.isPlaying(1), cpuCores = Runtime.getRuntime().availableProcessors())
   // plan.parallel luồng; mỗi stem: PcmTrackCache.writer riêng + Engine.stemAppendOwned(deck, owner, type, …)
   // nghỉ plan.pauseMs mỗi 16 mẻ khi có deck đang phát
   ```
   **Phải kiểm tra trước:** `Engine.stemAppendOwned` (native) có an toàn khi các type khác nhau append cùng lúc không. Chạy lại test TSan với 4 luồng append. Nếu không an toàn: giải mã song song vào hàng đợi, một luồng append.
4. `PcmTrackCache.replay` đã cho "/4 từ cache", nên lần sau LOAD lại bài đó gần như tức thì. Giữ nguyên.
5. LOG: thời gian tách, giải mã từng stem và số luồng. Lab đã có dòng "4 stem, N luồng" để so.

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
- **⚙ → "Cột BROWSE":** checkbox cho từng `ColumnId`, lưu bằng `BrowserPrefs`. Mặc định: Title, Artist, BPM, Key, MyTag, Time, STEMS.
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

## 4. Các file trong repo

```
shared/src/main/kotlin/com/xdj/flx10test/
  sd/SafDocId.kt              dựng documentId/URI không query; filePath() cho java.io.File
  sd/SafFastList.kt           SAF 1 query/thư mục, stat(), resolve(); FileList (java.io.File)
  sd/ParallelWalker.kt        duyệt cây song song (DirSource: SAF hoặc File)
  sd/SdScanCache.kt           đệm quét: save/load nhị phân, diff mtime, refresh chỉ thư mục đổi
  sd/StemDecodeScheduler.kt   chọn số stem giải mã song song theo codec/CPU/deck đang phát
  sd/ParallelStemDecoder.kt   MediaExtractor+MediaCodec nhiều luồng (Lab dùng để đo)
  browse/CamelotKey.kt        CamelotKey, KeyMatch, MasterKey
  browse/MyTags.kt            tách My Tag từ comment "/* … */"
  browse/BrowserColumns.kt    BrowserRow, ColumnId, SortState, định dạng/sắp xếp/chia độ rộng cột
  browse/TrackRowView.kt      TrackRowView, BrowserHeaderView, BrowserStyle (màu xanh key)
  browse/BrowserPrefs.kt      lưu cột + sắp xếp
  ui/SettingsToggleSection.kt mục "Chế độ & bố cục" trong ⚙
lab/                          FLX10 Lab (applicationId com.xdj.flx10test.lab)
handoff/                      file này + browse-preview.png
```

- Package con (`.sd`, `.browse`, `.ui`) không trùng tên lớp nào của 0.15.0.
- Chỉ dùng framework Android, không thêm AndroidX.
- Cùng AGP 8.5.2, Kotlin 1.9.24, minSdk 29, compileSdk 34 như app.

## 5. Test đã chạy (cloud, Linux)

- **`./gradlew :shared:testDebugUnitTest :lab:testDebugUnitTest`:** 52 + 8 test **PASS**, gồm cả Robolectric SDK 34. Các nhóm:
  - `CamelotKey`/`KeyMatch`: parse mọi dạng key, vòng 12↔1, A↔B, transpose, tempo → nửa cung;
  - `MyTags`: tag tiếng Việt, khối nằm giữa comment, trùng tag;
  - `SafDocId`: tên có dấu cách, `#`, `%`, NFD;
  - `ParallelWalker`: chạy song song thật, bỏ file `._`, báo lỗi;
  - `SdScanCache`: không đổi thì chỉ stat; đổi thì liệt kê lại đúng thư mục; xoá thư mục; lưu/đọc;
  - `StemDecodeScheduler`;
  - `BrowserColumns`: sắp xếp, định dạng, chia cột;
  - Views: `TrackRowView`, `BrowserHeaderView`, `SettingsToggleSection`, `BrowserPrefs`;
  - Lab: đọc rekordbox XML, tách `.serato-stems`, khởi động Activity.
- **`:shared:lintDebug`, `:lab:lintRelease`:** 0 lỗi, 0 cảnh báo.
- **APK Lab:** `apksigner` đạt (v2), `aapt2` (`com.xdj.flx10test.lab` 1/0.15.1-lab1, target 34), `zipalign -c -p 4` đạt.
- **Chưa làm được ở cloud:**
  - chạy trên tablet;
  - đo trên thẻ thật;
  - test native ASan/UBSan/TSan;
  - Robolectric SDK 29.

## 6. FLX10 Lab: thử trên tablet trước khi ghép

- **Cài đặt:** cài `FLX10-Lab-0.15.1-lab1.apk`. Lab có tên gói riêng nên **không đụng FLX10 DJ**, không phải gỡ gì.
- **Key ký:** Lab ký bằng key tạm (`CN=FLX10 Lab (key tam thoi)`, SHA-256 `e2d1bac1…d697cf34`). Key tạm không commit vì repo public. Build lại Lab trên Mac thì ký bằng key khác, nên cần gỡ Lab cũ trước khi cài.

**Các bước:**
1. Mở Lab → **Chọn thư mục Music** (cùng thư mục như FLX10 DJ).
2. **↻ Quét:** đo quét đầy đủ (liệt kê, XML, ghép).
3. Thoát hẳn Lab, mở lại: Lab tự **mở từ đệm** và đo thời gian danh sách hiện ra + thời gian kiểm tra thẻ.
4. Chọn một bài có STEMS → **Đo stems**: đo tách + giải mã 4 stem với 1/2/3/4 luồng.
5. **Đo resolve**: cách mới so với cách cũ (liệt kê `All Music DJ/`).
6. (Tuỳ chọn) **SAF vs File**: cần bật "Quyền truy cập mọi tệp" cho Lab.
7. Giữ lâu một bài để đặt làm MASTER, hoặc bấm **MASTER** để chọn key, tempo % và MT. Xem cột Key đổi màu xanh. **Chỉ bài hợp key** lọc danh sách. **⚙ Cột** bật/tắt cột. Bấm tiêu đề cột để sắp xếp.
8. Thoát Lab, cắm thẻ vào Mac: LOG nằm ở `Music/LOG/LAB_<thời gian>.log`.

**Đọc LOG Lab:**
- "Liệt kê All Music DJ: … ms": so với 28–44 s của 0.15.0.
- "Mở lại có đệm: … danh sách sẵn sàng sau … ms · kiểm tra … thư mục … ms": phần kiểm tra chạy nền.
- "Đo resolve … mới: trung bình … ms · cũ (liệt kê …): … ms": cách cũ chính là `resolve=10984ms`.
- "4 stem, N luồng: … ms": chọn số luồng cho `StemDecodeScheduler`. Dòng có "← app sẽ dùng" là số luồng `plan()` chọn với `codecLimit 3`. Nếu 3–4 luồng nhanh hơn rõ, cân nhắc nâng `codecLimit` khi không có deck đang phát.
- "bài gốc: … ms": tốc độ giải mã thô của máy. So với `decodeTotal` trong LOG app sẽ biết pipeline app (PcmTrackCache, chuyển PCM, admission) tốn thêm bao nhiêu.
- **Kiểm tra mtime thư mục khi thêm bài từ Mac:** thêm 1 bài vào `All Music DJ/<thư mục con>`, cắm lại thẻ, mở Lab. Nếu dòng "Mở lại có đệm" ghi "liệt kê lại 1" thì đệm theo mtime dùng được trên exFAT.

## 7. Phát hành 0.15.1 (trên Mac)

- versionCode **47**, versionName **0.15.1**. Ký bằng key thật (cùng key 0.14.10/0.15.0) để cài đè.
- Viết `GHI-CHU-0.15.1.md` theo mẫu 0.15.0 (Cài đặt · Có gì mới · Cần thử trên tablet · Kiểm tra đã chạy). Có gì mới:
  - thẻ SD nhanh hơn (resolve, mở đệm, quét song song);
  - stems bắt đầu ngay sau READY, giải mã song song, không bị bỏ khi deck đang phát;
  - BROWSE chia cột, sắp xếp theo cột, chọn cột trong ⚙;
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
