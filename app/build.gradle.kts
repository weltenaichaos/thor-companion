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
        versionCode = 12
        versionName = "0.9.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the CI build installs as is; no release key yet.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":decoder"))
}
