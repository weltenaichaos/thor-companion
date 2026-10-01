plugins {
    // The Android plugin is declared in app/ so the decoder builds without Google's repository.
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}
