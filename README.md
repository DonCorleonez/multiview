# Multiview

Multiviewer live TV Xtream Codes untuk **Google TV Streamer 4K**. Ada 1, 2, 3, atau 4 layar sekaligus. Tiap layar terikat ke satu akun Xtream (1 akun = 1 koneksi). Audio keluar dari layar yang di-highlight. Kualitas mengikuti sumber channel. Tanpa EPG, VOD, maupun tampilan kosmetik.

## Remote

| Tombol | Fungsi |
|---|---|
| D-pad | Pindah highlight antar layar. Audio ikut pindah |
| OK | Pilih channel untuk layar yang di-highlight (kategori, cari, riwayat) |
| Tahan OK | Menu: Layout 1/2/3/4 · Akun · Muat ulang daftar channel · Keluar |
| Kembali | Menutup panel. Di layar utama, tekan 2× untuk keluar |

Layout 1 menampilkan layar yang sedang di-highlight secara penuh. Slot yang tidak tampil dihentikan, sehingga koneksi akunnya ikut dilepas.

## Pasang

1. Unduh `multiview-<versi>.apk` dari halaman **Releases** repo ini.
2. Di Streamer, buka Settings › System › About, lalu tekan *Android TV OS build* 7× untuk membuka Developer options. Nyalakan **Wireless debugging**, lalu pilih *Pair device with pairing code*.
3. Di Termux:
   ```bash
   adb pair <ip>:<port-pair> <kode>
   adb connect <ip>:<port>
   adb install -r multiview-<versi>.apk
   ```
4. Matikan Wireless debugging setelah selesai.

Instalasi lewat ADB dikecualikan dari aturan verifikasi developer Google di Indonesia.

Pertama kali dibuka: tahan OK › **Akun**, lalu isi server (`http://host:port`), username, dan password untuk Akun 1–4, kemudian tekan **Tes** dan **Simpan**. Mengetik akan lebih mudah lewat keyboard aplikasi Google TV di HP.

APK `-debug` bisa dipasang berdampingan dengan versi rilis. Versi ini dipakai untuk mengukur performa decoder: `adb logcat -s MV/Player`.

## Signing (sekali saja, sebelum pemasangan pertama)

Tambahkan dua secret di GitHub › Settings › Secrets and variables › Actions:

- `MV_KEYSTORE_B64`: isi keystore dalam bentuk base64
- `MV_STORE_PASSWORD`: password keystore

Dengan kunci yang sama, update cukup dipasang dengan `adb install -r` dan akun tetap tersimpan. Simpan file keystore di tempat aman dan jangan di-commit.

## Pengembangan

- `./gradlew testDebugUnitTest`: unit test (mock server Xtream, engine, layout, redaksi, penyimpanan)
- CI (`.github/workflows/build.yml`): test, lint, build, cek APK (izin, manifest, ukuran), lalu Release otomatis setiap push ke `main`
- Spesifikasi dan catatan implementasi ada di [docs/MULTIVIEW_HANDOFF.md](docs/MULTIVIEW_HANDOFF.md); status terakhir dan langkah lanjutan untuk Codex ada di [docs/CODEX_HANDOFF.md](docs/CODEX_HANDOFF.md)

Keamanan: password dienkripsi di Android Keystore, kredensial tidak pernah ditulis ke log, ada `FLAG_SECURE` di layar akun, tidak ada backup, tidak ada telemetri.

Lisensi MIT.
