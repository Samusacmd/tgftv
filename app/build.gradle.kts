plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.telegramfiretv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.telegramfiretv"
        minSdk = 21
        targetSdk = 34
        // ---- Versionamento semantico (SemVer): MAJOR.MINOR.PATCH ----
        // Da incrementare A MANO prima di ogni release:
        //   PATCH (x.y.Z) per correzioni di bug
        //   MINOR (x.Y.0) per nuove funzionalità
        //   MAJOR (X.0.0) per cambi radicali/incompatibili
        // NOTA: senza incremento, il tasto Update dirà "già aggiornato".
        val semVer = "1.5.25"
        // Il versionCode deve solo crescere sempre (requisito Android per gli update):
        // resta agganciato al numero progressivo della build CI, nessuna gestione manuale.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = semVer

        buildConfigField("int", "API_ID", project.findProperty("API_ID")?.toString() ?: "0")
        buildConfigField("String", "API_HASH", "\"${project.findProperty("API_HASH") ?: ""}\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Client HTTP per l'aggiornamento: consente di risolvere i nomi con il DNS scelto
    // dall'utente (AppDns) mantenendo TLS e verifica certificati corretti.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.airbnb.android:lottie:6.4.0")
}
