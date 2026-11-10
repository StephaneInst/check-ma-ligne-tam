plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.perso.tamligne"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.perso.tamligne"
        minSdk = 26
        targetSdk = 34
        // Numéro de version fourni par la compilation GitHub (1.2.<n° de compilation>)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = System.getenv("APP_VERSION") ?: "1.2.0"
    }

    // Clé fixe versionnée : chaque APK compilé par GitHub a la même signature,
    // on peut donc installer les mises à jour par-dessus sans désinstaller.
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.9.1")
}
