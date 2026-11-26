# EFOS-Ofis v2.2 — Windows Masaüstü + Google Antigravity MCP + Android

**EFOS-Ofis**, temel ofis uygulamalarını (Word, Excel, PowerPoint, PDF, Markdown, ZIP), **Google Antigravity MCP** sunucusunu ve **Android** uygulamasını bir araya getirir.

## v2.2'de neler değişti
- **Açılan dosyalar artık bozulmuyor.** Var olan bir Word, Excel veya PowerPoint dosyası kaydedilirken sıfırdan yazılmıyor; orijinal dosya güncelleniyor.
  - **Excel:** Yalnızca değişen hücreler yazılır. Sayı biçimleri (`#,##0.00 ₺`), tarihler, birleştirilmiş hücreler, sütun genişlikleri ve diğer sayfalar korunur. `00123` gibi baştaki sıfırlı numaralar ve 15 haneden uzun numaralar (hesap no, IBAN parçası) metin olarak kalır. Grafik veya resim içeren dosyalarda, üzerine yazmadan önce uyarı gelir.
  - **Word:** Üstbilgi/altbilgi, sayfa yapısı, stiller, resimler, madde işaretli ve numaralı listeler korunur. Dipnot, yorum ve metin kutusu içeren belgelerde uyarı gelir.
  - **PowerPoint:** Değiştirilmeyen slaytlara dokunulmaz. Grafik, resim, tablo ve tema korunur; değişen slaytlarda yazı biçimi kalır.
- **Türkçe formüller:** `=YUVARLA(2,345;2)` gibi virgüllü ondalıklar, `1.234,56` yazımı ve `%18` çalışır. Yuvarlama Excel ile aynıdır (`YUVARLA(2,5;0)=3`). `^` işleci soldan sağa işlenir. Metin karşılaştırmaları büyük/küçük harfe duyarsızdır. `&`, `%` ve `A:A` kullanılabilir.
  - **Yeni fonksiyonlar:** `DÜŞEYARA`, `YATAYARA`, `İNDİS`, `KAÇINCI`, `EĞERHATA`, `EĞERSAY`, `ETOPLA`, `VE`, `YADA`, `BİRLEŞTİR`, `SOLDAN`/`SAĞDAN`/`PARÇAAL`, `BUGÜN`, `YIL`/`AY`/`GÜN`, `YUKARIYUVARLA`/`AŞAĞIYUVARLA` ve diğerleri.
  - **Excel uyumu:** Formüller dosyaya Excel'in İngilizce biçiminde yazılır (`=ROUND(A1*1.18,2)`) ve ekranda Türkçe gösterilir.
- **MCP güvenliği:** Araçlar yalnızca izin verilen klasörlerde çalışır (varsayılan: kullanıcı klasörünüz). Üzerine yazılan dosyaların önce `.efos-yedek` kopyası alınır. Bozuk Antigravity ayar dosyası silinmez; önce yedeklenir.
- **EXE:** Antigravity bağlantısı EXE'den de çalışır (`EFOS-Ofis.exe --mcp`). Kullanılmayan Qt parçaları pakete girmez. İsteğe bağlı klasör sürümü çok daha hızlı açılır.
- **Android:** Tek sürüm kaldı (yerel Java). Dosya işlemleri arka planda yapılır. Telefondan açılan dosya kaydedilince asıl yerine de yazılır. **Telefona Kaydet…** ve **Paylaş** düğmeleri eklendi.
- **ZIP:** Sıkıştırma bombasına karşı boyut sınırı var. Hatalı bir arşivden yarım dosya kalmaz.

## 1. Windows'ta çalıştırma ve `.exe` derleme
1. Python 3.10+ kurulu olmalı. Doğrudan çalıştırmak için **`baslat.bat`**.
2. **`exe_yap.bat`**, testleri çalıştırır ve **`dist\EFOS-Ofis.exe`** dosyasını üretir (tek dosya).
3. **`exe_yap.bat klasor`**, **`dist\EFOS-Ofis\`** klasörünü üretir. Bu sürüm daha hızlı açılır ve MCP için `efos-mcp.exe` içerir.
4. `exe_yap.bat` artık `dist` klasörünü tamamen silmez; `dist\EFOS-Ofis.apk` korunur.

> EXE dijital olarak imzalı değildir. İlk açılışta Windows SmartScreen uyarısı çıkarsa **Ek bilgi → Yine de çalıştır** seçin.

## 2. Google Antigravity bağlantısı (MCP)
- Uygulamadaki **⚡ Antigravity MCP** düğmesini kullanın ya da **`antigravity_bagla.bat`** dosyasını çalıştırın. Belirli bir klasöre izin vermek için: `antigravity_bagla.bat "D:\Belgeler"`.
- Kayıt `~/.gemini/config/mcp_config.json` dosyasına yazılır; oradaki diğer sunucular korunur.
- İzinli klasörler `EFOS_MCP_ALLOWED_DIRS` ortam değişkeniyle belirlenir (`;` ile ayrılır, `*` sınırsız demektir).
- **23 araç:**
  - **Excel / CSV:** `excel_read`, `excel_write`, `csv_to_excel`, `excel_to_csv`
  - **Word:** `word_read`, `word_write`, `word_find_replace`
  - **PowerPoint:** `pptx_read`, `pptx_write`
  - **PDF:** `pdf_read_and_search`, `pdf_write`, `pdf_merge`, `pdf_split`, `pdf_rotate`, `pdf_to_images`, `pdf_metadata`
  - **ZIP:** `zip_extract`, `zip_create`, `zip_list`
  - **Diğer:** `office_to_pdf`, `office_search_folder`, `markdown_note`, `image_office_tool`

## 3. Android `.apk`
Android uygulaması `android_native/` klasöründedir: saf Java, Gradle gerektirmez, çok küçüktür.
- **Windows'ta:** Android SDK (build-tools + platform) ve JDK kurulu olmalı. `powershell -ExecutionPolicy Bypass -File android_native\apk_derle.ps1` komutu **`dist\EFOS-Ofis.apk`** dosyasını üretir.
- **Linux / GitHub Actions:** `bash android_native/apk_derle.sh`
- **İmza:** Varsayılan olarak `android_native/debug.keystore` kullanılır (kişisel kurulum için). Bu dosyayı yedekleyin; kaybolursa yeni sürümler eskisinin üzerine kurulamaz.
- **Google Play için:** `android_native\release_anahtari_olustur.ps1` ile kendi yayın anahtarınızı üretin. Ardından `EFOS_KEYSTORE`, `EFOS_KEY_ALIAS` ve `EFOS_KEYSTORE_PASS` değişkenlerini ayarlayıp derleyin. Yayın anahtarı ve parolası depoya konmaz.

## 4. Otomatik derleme (GitHub Actions)
`.github/workflows/efos-ofis.yml` iş akışı her gönderimde şunları yapar:
1. Testleri çalıştırır.
2. EXE'yi, klasör sürümünü ve APK'yı derler.
3. EXE'nin MCP kipini dener.
4. Sonuçları **Releases → `efos-ofis-son-surum`** altına koyar.

İsteğe bağlı yayın imzası için şu depo sırları kullanılır:
- `EFOS_RELEASE_KEYSTORE_B64`
- `EFOS_RELEASE_KEY_ALIAS`
- `EFOS_RELEASE_KEYSTORE_PASS`
- `EFOS_RELEASE_KEY_PASS`

## 5. Testler
```
set QT_QPA_PLATFORM=offscreen
python -m unittest discover -s tests
```
Android çekirdek testleri (`android_native/test/SelfTest.java`) APK derleme betiklerinin ilk adımında otomatik çalışır.

## 6. Bilinen sınırlar
- **Word düzenleyici:** HTML tabanlıdır. Dipnot, yorum, metin kutusu, içindekiler alanı ve izlenen değişiklikler taşınamaz; bu durumda kaydetmeden önce uyarı verilir.
- **Excel (masaüstü):** openpyxl grafik ve resimleri koruyamaz. Bu tür dosyalar için uyarı çıkar ve **Farklı Kaydet** önerilir. Android sürümü dosyayı doğrudan yamaladığı için grafikleri korur.
- **Android:** Yalnızca ilk sayfa düzenlenir; diğer sayfalar olduğu gibi korunur. Word düzenleyicisi düz metindir ama paragraf biçimleri korunur.
