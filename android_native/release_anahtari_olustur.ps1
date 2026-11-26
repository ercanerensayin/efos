$ErrorActionPreference = "Stop"
# Google Play / kalici dagitim icin YAYIN imza anahtari uretir.
# ONEMLI: Bu dosyayi ve parolasini guvenli bir yerde yedekleyin. Kaybederseniz uygulamanizi
# ayni adla bir daha guncelleyemezsiniz. Anahtari proje klasorune veya GitHub'a KOYMAYIN.

$Default = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "EFOS-Anahtarlar\efos-yayin.jks"
$Path = Read-Host "Anahtar dosyasi yolu [$Default]"
if (-not $Path) { $Path = $Default }
if (Test-Path $Path) { throw "Bu konumda zaten bir anahtar var: $Path (ustune yazilmadi)" }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null

$Sec = Read-Host "Anahtar parolasi (en az 8 karakter)" -AsSecureString
$Pass = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($Sec))
if ($Pass.Length -lt 8) { throw "Parola en az 8 karakter olmali." }
$Name = Read-Host "Ad Soyad (sertifikada gorunur)"
if (-not $Name) { $Name = "EFOS-Ofis" }

& keytool -genkeypair -keystore $Path -storetype PKCS12 -storepass $Pass -keypass $Pass `
    -alias efos-yayin -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=$Name, O=EFOS-Ofis, C=TR"
if ($LASTEXITCODE -ne 0) { throw "keytool hatasi" }

Write-Host ""
Write-Host "[BASARILI] Yayin anahtari olusturuldu: $Path"
Write-Host "Yayin APK'si icin PowerShell'de:"
Write-Host "  `$env:EFOS_KEYSTORE = '$Path'"
Write-Host "  `$env:EFOS_KEY_ALIAS = 'efos-yayin'"
Write-Host "  `$env:EFOS_KEYSTORE_PASS = '<parolaniz>'"
Write-Host "  .\android_native\apk_derle.ps1"
Write-Host ""
Write-Host "Not: Yayin anahtariyla imzalanan APK, debug anahtariyla kurulmus surumun uzerine kurulamaz;"
Write-Host "gecis sirasinda telefondaki eski surumu bir kez kaldirmaniz gerekir."
