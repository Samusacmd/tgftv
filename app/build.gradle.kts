plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Le credenziali Telegram arrivano da -PAPI_ID / -PAPI_HASH (impostate dai
// GitHub Secrets nella CI). In locale restano a 0/"" e l'app compila lo stesso.
val apiId: String = (project.findProperty("API_ID") as String?) ?: "0"
val apiHash: String = (project.findProperty("API_HASH") as String?) ?: ""

// versionCode che cresce a ogni build: usa il numero progressivo della CI.
// Così ogni nuovo APK è "più recente" e si installa SOPRA il precedente.
val ciRun: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.telegramfiretv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.telegramfiretv"
        minSdk = 21
        targetSdk = 34
        versionCode = ciRun
        versionName = "1.0.$ciRun"
        buildConfigField("int", "API_ID", apiId)
        buildConfigField("String", "API_HASH", "\"$apiHash\"")
        // Limita gli ABI a quelli reali dei Fire TV (32 e 64 bit ARM).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Firma stabile: usa sempre la stessa chiave committata in app/debug.keystore,
    // così gli aggiornamenti si installano senza disinstallare la versione vecchia.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    // Player (ExoPlayer / Media3)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
