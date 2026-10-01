pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ThorCompanion"
include(":decoder")

// The app needs the Android SDK. Without one (ANDROID_HOME or local.properties)
// only the plain-Kotlin decoder is built, so its tests run anywhere.
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
) {
    include(":app")
}
