plugins {
    // 8.10.1: amneziawg-android 2.3.7 pulls core-ktx 1.17.0, whose AAR metadata
    // demands compileSdk 36 and AGP 8.9.1+; 8.10.1 is the newest AGP that still
    // runs on the Gradle 8.11.1 wrapper shipped here.
    id("com.android.application") version "8.10.1" apply false
    // 2.2.21: hevtunnel (transitive of amneziawg 2.3.7) forces kotlin-stdlib 2.2.21,
    // and a 1.9 compiler cannot read its metadata.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    // Required since Kotlin 2.0: the Compose compiler ships as a separate plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
