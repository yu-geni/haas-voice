plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.haasvoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.haasvoice"
        minSdk = 29
        targetSdk = 35
        // 旧リポジトリのビルド（#1〜#20 程度）より新しい番号にして、上書き更新できるようにする
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt() + 100
        versionCode = build
        versionName = "0.1.$build"
        ndk { abiFilters += "arm64-v8a" }
    }

    // 固定の鍵で署名して、毎回アンインストールせずに上書き更新できるようにする。
    // CI では GitHub Secrets の鍵を使う。鍵がなければ Android の debug 鍵で署名する
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
    val keystorePath = env("SIGNING_KEYSTORE_PATH")
    val keystoreFile = keystorePath?.let { file(it) }
    signingConfigs {
        if (keystoreFile != null) {
            create("shared") {
                storeFile = keystoreFile
                storePassword = env("SIGNING_PASSWORD")
                keyAlias = env("SIGNING_KEY_ALIAS") ?: "haasvoice"
                keyPassword = env("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        debug { signingConfig = signing }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
