plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.ercan.efos"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ercan.efos"
        minSdk = 26; targetSdk = 34
        versionCode = 1; versionName = "1.0"
        // Python + yt-dlp + FFmpeg yerel kütüphaneleri: yalnızca telefon işlemcileri
        // Varsayılan: 64-bit ARM telefonlar. Emülatör testi için: ./gradlew assembleDebug -Pabi=x86_64
        ndk { abiFilters += (project.findProperty("abi") as String? ?: "arm64-v8a").split(",") }
    }
    signingConfigs {
        create("sabit") {
            storeFile = file("debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("sabit") }
        debug { signingConfig = signingConfigs.getByName("sabit") }
    }
    // Windows sürümüyle ORTAK dosya (renkler, metinler, yt-dlp ayarları, hata çevirileri)
    sourceSets["main"].assets.srcDirs("../../ortak")
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
