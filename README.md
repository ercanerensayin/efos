# OtoMakro – MacroDroid benzeri Android otomasyon uygulaması

Makro = **Tetikleyici → (Koşullar) → Eylemler**. Harici kütüphane kullanmaz (yalnızca Android SDK + Kotlin), Android 8.0 (API 26) ve üzeri.

## Özellikler

**Tetikleyiciler (11):** belirli saat (gün seçimli), dakika aralığı, pil seviyesi altına/üstüne geçiş, şarj takma/çıkarma, ekran açma/kapama/kilit açma, kablolu kulaklık, Wi-Fi bağlanma/kopma (isteğe bağlı ağ adı), uçak modu, cihaz açılışı, bildirim gelmesi (uygulama + metin filtresi), cihazı sallama.

**Koşullar (7):** saat aralığı (gece yarısını geçebilir), haftanın günleri, pil seviyesi, şarj durumu, ekran durumu, Wi-Fi durumu, kulaklık durumu.

**Eylemler (15):** bildirim, toast, sesli okuma (Türkçe TTS), titreşim, zil modu, Rahatsız Etme, ses düzeyi, fener, parlaklık, uygulama açma, web adresi açma, medya oynat/duraklat/sonraki, ses çalma, panoya kopyalama, bekleme.

**Değişkenler:** metinlerde `{saat} {tarih} {pil} {wifi} {bildirim_baslik} {bildirim_metin} {uygulama}`.

Ayrıca: makroları açıp kapatma, elle çalıştırma, eylem sırasını değiştirme, günlük ekranı, izin yönetimi ekranı, 6 hazır örnek makro.

## APK nasıl alınır?

### Yol 1 – GitHub (bilgisayara bir şey kurmadan)
1. github.com'da yeni bir depo açın ve bu klasörün içeriğini yükleyin (`.github` klasörü dahil).
2. Depoda **Actions** sekmesine gidin; “APK Derle” iş akışı kendiliğinden çalışır (ya da *Run workflow*).
3. ~3–5 dakika sonra çalışmanın sayfasındaki **Artifacts → OtoMakro-apk** dosyasını indirin, zip'i açın, `app-debug.apk`'yı telefona kurun.

### Yol 2 – Android Studio
1. Android Studio'da **Open** ile bu klasörü açın, Gradle eşitlemesini bekleyin.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. APK: `app/build/outputs/apk/debug/app-debug.apk`

## İlk kurulumdan sonra
- Bildirim iznini verin.
- Menü → **İzinler**: “Pil optimizasyonundan muaf” mutlaka açın. Kullandığınız özelliklere göre diğerlerini açın.
- Xiaomi / Huawei / Oppo / Vivo cihazlarda uygulama ayarlarından **Otomatik başlatma** iznini de verin; yoksa sistem servisi kapatabilir.

## Bilinen sınırlar
- Wi-Fi / Bluetooth'u açıp kapatmak Android 10+'da üçüncü taraf uygulamalara yasak olduğu için eklenmedi.
- Sallama algılaması ekran kapalıyken bazı cihazlarda çalışmaz (sensörler uyutulur).
- Arka plandan uygulama/web açmak için “Diğer uygulamaların üzerinde gösterme” izni gerekir; izin yoksa dokunulabilir bir bildirim gösterilir.
