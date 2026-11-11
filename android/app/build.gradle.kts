import java.util.Properties

plugins {
    id("com.android.application")
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "ai.gilmok.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.gilmok.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("release") {
            // keystore and passwords stay in local.properties (not committed)
            local.getProperty("release.storeFile")?.let { storeFile = file(it) }
            storePassword = local.getProperty("release.storePassword")
            keyAlias = local.getProperty("release.keyAlias")
            keyPassword = local.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // CarPropertyManager (fuel capacity) lives in the platform's android.car library
    useLibrary("android.car")
}

dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
}
