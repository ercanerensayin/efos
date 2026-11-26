$ErrorActionPreference = "Stop"

# EFOS-Ofis Android APK derleyicisi (Gradle gerektirmez).
#
# Imza:
#   - Varsayilan: android_native\debug.keystore (kisisel kullanim / hata ayiklama anahtari)
#   - Yayin (Google Play vb.): once release_anahtari_olustur.ps1 ile kendi anahtarinizi uretin,
#     sonra su ortam degiskenlerini ayarlayip bu betigi calistirin:
#       $env:EFOS_KEYSTORE      = "C:\Guvenli\efos-yayin.jks"
#       $env:EFOS_KEY_ALIAS     = "efos-yayin"
#       $env:EFOS_KEYSTORE_PASS = "<parola>"
#       $env:EFOS_KEY_PASS      = "<parola>"   (bossa EFOS_KEYSTORE_PASS kullanilir)
#
# Cikti: <proje>\dist\EFOS-Ofis.apk  (exe_yap.bat bu dosyayi artik silmez)

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $Root
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }

function Get-Newest($dir, $pattern) {
    if (-not (Test-Path $dir)) { return $null }
    Get-ChildItem -Directory $dir | Where-Object { $_.Name -match $pattern } |
        Sort-Object { [version](($_.Name -replace '[^0-9.]', '') + '.0') } -Descending |
        Select-Object -First 1
}

$Platform = Join-Path $Sdk "platforms\android-35"
if (-not (Test-Path $Platform)) {
    $p = Get-Newest (Join-Path $Sdk "platforms") '^android-\d+$'
    if ($p) { $Platform = $p.FullName }
}
$BuildTools = Join-Path $Sdk "build-tools\35.0.0"
if (-not (Test-Path $BuildTools)) {
    $b = Get-Newest (Join-Path $Sdk "build-tools") '^\d+\.\d+\.\d+$'
    if ($b) { $BuildTools = $b.FullName }
}

$AndroidJar = Join-Path $Platform "android.jar"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8Jar = Join-Path $BuildTools "lib\d8.jar"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSignerJar = Join-Path $BuildTools "lib\apksigner.jar"

foreach ($req in @($AndroidJar, $Aapt2, $D8Jar, $ZipAlign, $ApkSignerJar)) {
    if (-not (Test-Path $req)) {
        throw "Gerekli Android SDK bileseni bulunamadi: $req"
    }
}

$BuildDir = Join-Path $Root "build"
$ResCompiled = Join-Path $BuildDir "res_compiled"
$GenDir = Join-Path $BuildDir "gen"
$ObjDir = Join-Path $BuildDir "obj"
$TestObjDir = Join-Path $BuildDir "test_obj"
$DexDir = Join-Path $BuildDir "dex"
$DistDir = Join-Path $ProjectRoot "dist"

if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $ResCompiled, $GenDir, $ObjDir, $TestObjDir, $DexDir, $DistDir | Out-Null

Write-Host "[1/6] Cekirdek motor birim testleri calistiriliyor..."
& javac -J-Xmx256m -encoding UTF-8 -nowarn -d $TestObjDir `
    (Join-Path $Root "src\org\efosofis\mobile\FormulaEngine.java") `
    (Join-Path $Root "src\org\efosofis\mobile\OfficeEngines.java") `
    (Join-Path $Root "test\org\efosofis\mobile\SelfTest.java")
if ($LASTEXITCODE -ne 0) { throw "Test derleme hatasi" }

& java -Xmx256m -cp $TestObjDir org.efosofis.mobile.SelfTest
if ($LASTEXITCODE -ne 0) { throw "Cekirdek testleri basarisiz" }

Write-Host "[2/6] Android kaynaklari (aapt2) derlenip baglaniyor..."
$ResZip = Join-Path $ResCompiled "resources.zip"
& $Aapt2 compile --dir (Join-Path $Root "res") -o $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile hatasi" }

$BaseApk = Join-Path $BuildDir "base.apk"
& $Aapt2 link `
    -I $AndroidJar `
    --manifest (Join-Path $Root "AndroidManifest.xml") `
    --java $GenDir `
    -o $BaseApk `
    $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link hatasi" }

Write-Host "[3/6] Android Java kaynaklari derleniyor (Java 8 bytecode)..."
$JavaSources = @(Get-ChildItem -Recurse -Filter "*.java" -Path $GenDir, (Join-Path $Root "src") | ForEach-Object { $_.FullName })
& javac -J-Xmx384m -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options `
    -classpath $AndroidJar `
    -d $ObjDir `
    @JavaSources
if ($LASTEXITCODE -ne 0) { throw "javac derleme hatasi" }

Write-Host "[4/6] Dalvik bytecode (classes.dex) olusturuluyor (d8)..."
$ClassFiles = @(Get-ChildItem -Recurse -Filter "*.class" -Path $ObjDir | ForEach-Object { $_.FullName })
& java -Xmx512m -cp $D8Jar com.android.tools.r8.D8 `
    --release `
    --min-api 24 `
    --lib $AndroidJar `
    --output $DexDir `
    @ClassFiles
if ($LASTEXITCODE -ne 0) { throw "d8 donusum hatasi" }

Write-Host "[5/6] classes.dex APK icine paketleniyor ve hizalaniyor (zipalign)..."
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Archive = [System.IO.Compression.ZipFile]::Open($BaseApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $DexFile = Join-Path $DexDir "classes.dex"
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $DexFile, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $Archive.Dispose()
}

$AlignedApk = Join-Path $BuildDir "aligned-imzasiz.apk"
& $ZipAlign -p -f 4 $BaseApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign hatasi" }

Write-Host "[6/6] APK imzalaniyor ve dogrulaniyor (apksigner)..."
if ($env:EFOS_KEYSTORE) {
    $Keystore = $env:EFOS_KEYSTORE
    $Alias = if ($env:EFOS_KEY_ALIAS) { $env:EFOS_KEY_ALIAS } else { "efos-yayin" }
    $StorePass = $env:EFOS_KEYSTORE_PASS
    $KeyPass = if ($env:EFOS_KEY_PASS) { $env:EFOS_KEY_PASS } else { $StorePass }
    if (-not $StorePass) { throw "EFOS_KEYSTORE ayarli ama EFOS_KEYSTORE_PASS bos." }
    if (-not (Test-Path $Keystore)) { throw "Yayin anahtari bulunamadi: $Keystore" }
    Write-Host "      Yayin anahtari kullaniliyor: $Keystore ($Alias)"
} else {
    $Keystore = Join-Path $Root "debug.keystore"
    $Alias = "efosofis"
    $StorePass = "android"
    $KeyPass = "android"
    if (-not (Test-Path $Keystore)) {
        Write-Host "      UYARI: debug.keystore yok, yenisi uretiliyor. Bu anahtarla imzalanan APK,"
        Write-Host "      eski anahtarla kurulmus surumun uzerine KURULAMAZ (once eskisini kaldirin)."
        & keytool -genkeypair -keystore $Keystore -storepass $StorePass -keypass $KeyPass `
            -alias $Alias -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=EFOS-Ofis, O=EFOS, C=TR"
        if ($LASTEXITCODE -ne 0) { throw "keytool keystore olusturma hatasi" }
    }
    Write-Host "      Kisisel (debug) anahtar kullaniliyor. Bu dosyayi yedekleyin: $Keystore"
}

$FinalApk = Join-Path $DistDir "EFOS-Ofis.apk"
& java -Xmx256m -jar $ApkSignerJar sign `
    --ks $Keystore `
    --ks-key-alias $Alias `
    --ks-pass "pass:$StorePass" `
    --key-pass "pass:$KeyPass" `
    --out $FinalApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign hatasi" }

& java -Xmx256m -jar $ApkSignerJar verify --print-certs $FinalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify hatasi" }

$Info = Get-Item $FinalApk
Write-Host "[BASARILI] Android APK olusturuldu: $($Info.FullName) ($([math]::Round($Info.Length / 1KB, 1)) KB)"
