# Appdj: phần mã mới cho FLX10 DJ 0.15.1

Mã nguồn chính của FLX10 DJ (`com.xdj.flx10test`) nằm trên Mac. Repo này chứa phần làm trên cloud cho 0.15.1, để ghép vào app thật:

- **`shared/`**: Android library, chỉ dùng framework Android, cùng AGP 8.5.2 / Kotlin 1.9 / minSdk 29 như app.
  - `sd/`: đọc thẻ SD nhanh (resolve không query, liệt kê SAF song song, đệm quét, giải mã stems song song);
  - `browse/`: BROWSE chia cột, sắp xếp, MyTag từ comment, key hợp với deck MASTER hiện xanh;
  - `ui/`: mục "Chế độ & bố cục" trong ⚙ (thay hàng nút thứ 2).
- **`lab/`**: FLX10 Lab, app thử trên tablet: xem trước BROWSE chia cột và đo tốc độ quét, resolve, stems trên thẻ thật. Ghi LOG vào `Music/LOG/`.
- **`handoff/TOM-TAT-0.15.1.md`**: tóm tắt và hướng dẫn ghép vào app thật trên Mac.

## Build & test

```sh
echo "sdk.dir=/đường/dẫn/android-sdk" > local.properties
./gradlew :shared:testDebugUnitTest :lab:testDebugUnitTest :shared:lintDebug :lab:lintRelease :lab:assembleRelease
```

Ký Lab: tạo `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) ở thư mục gốc. Không có file này thì Lab ký bằng debug key.
**Không commit keystore hay `keystore.properties`** (repo public; `.gitignore` đã chặn).
