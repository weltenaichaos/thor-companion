plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "thor.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "thor.companion"
        minSdk = 30
        targetSdk = 34
        versionCode = 37
        versionName = "0.17.9"
    }

    // One fixed debug key in the repo, so every CI build installs over the last one
    // (each runner would otherwise make its own key, and Android refuses that update,
    // which meant uninstalling and losing the saved item names, map pictures and paths).
    signingConfigs {
        getByName("debug") {
            storeFile = file("companion-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the CI build installs as is; no release key yet.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The addon goes into the APK, so the app can put the matching version into the game (AddonInstaller).
    sourceSets["main"].assets.srcDirs("../addon")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":decoder"))
}
