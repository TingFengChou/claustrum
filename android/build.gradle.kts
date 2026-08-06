// Root build — plugin versions pinned to the machine's known-good combo
// (Gradle 9.3.1 wrapper · AGP 8.12.0 · Kotlin 2.1.20), matching the RN app.
plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
