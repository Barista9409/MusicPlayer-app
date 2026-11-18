import java.util.Properties

// NewPipe Extractor の版(毎日の自動チェックで新しい版が出たら、ここが書き換わる)
val newpipeVersion = "v0.27.1"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定の署名鍵(上書きアップデートで履歴・プレイリストを保持するため)
val signingProps = Properties().apply {
    val f = rootProject.file("keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.ytmp3"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ytmp3"
        minSdk = 29
        targetSdk = 34
        versionCode = 329
        versionName = "10.57.6"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // 設定に版を出す
        buildConfigField("String", "NEWPIPE_VERSION", "\"${newpipeVersion.removePrefix("v")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("fixed") {
            storeFile = file(signingProps.getProperty("storeFile", "../keystore/ytmp3.jks"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    testOptions {
        // 自動テストで Android の部品に触れても落ちないように(既定値を返す)
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor は新しい Java の部品を使うので、古い Android でも動くように補う
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // youtubedl-android は同梱のネイティブ実行ファイルを展開して使うため必須
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    val ytdlVersion = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")

    // 2 つ目の取り方(yt-dlp と別のチームが作っている。速い)
    implementation("com.github.teamnewpipe:NewPipeExtractor:$newpipeVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3") // 動画プレビュー


    implementation("androidx.core:core-ktx:1.13.1")
    // ネット上のジャケット画像(検索結果・試聴)の読み込み
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1") // 毎日の自動バックアップ
    implementation("net.jthink:jaudiotagger:3.0.1") // 曲の情報をファイルのタグにも書き込む
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0") // 再生中の画面をジャケットの色に
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
