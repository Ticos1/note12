plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ticos.multiidle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ticos.multiidle"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Chave fixa no repositório: assim cada APK novo instala POR CIMA do anterior
    // (mesma assinatura) sem perder os logins. É um app pessoal, fora da Play Store.
    signingConfigs {
        create("pessoal") {
            storeFile = file("multiidle.keystore")
            storePassword = "multiidle"
            keyAlias = "multiidle"
            keyPassword = "multiidle"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pessoal")
        }
        debug {
            signingConfig = signingConfigs.getByName("pessoal")
        }
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
    implementation("androidx.webkit:webkit:1.12.1")
}
