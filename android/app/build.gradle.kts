plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ABIs to build the Rust core for (rustup targets: aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android).
val abis: List<String> = ((findProperty("abis") as String?) ?: "arm64-v8a,x86_64").split(",")

// The proxy core is the Rust crate in ../../rust-core, built with cargo-ndk into src/main/jniLibs.
val buildRustCore by tasks.registering(Exec::class) {
    workingDir = rootProject.file("../rust-core")
    val out = file("src/main/jniLibs")
    val args = mutableListOf("cargo", "ndk")
    abis.forEach { args += listOf("-t", it) }
    args += listOf("-o", out.absolutePath, "build", "--release")
    commandLine(args)
}
tasks.named("preBuild") { dependsOn(buildRustCore) }

// Core version comes from proxy/__init__.py so the app follows the desktop release.
val coreVersion: String = Regex("""__version__\s*=\s*"([^"]+)"""")
    .find(rootProject.file("../proxy/__init__.py").readText())
    ?.groupValues?.get(1) ?: "0.0.0"

// Release builds pass -PappVersion=<core>.<revision> (e.g. 1.11.4.2) so the app
// can ship its own fixes between core releases. versionCode must only ever grow,
// because the in-app updater installs over the previous APK.
val appVersion: String = (findProperty("appVersion") as String?)?.removePrefix("v") ?: coreVersion
val appVersionCode: Int = appVersion.split(".").map { it.toIntOrNull() ?: 0 }.let {
    (it.getOrElse(0) { 0 }) * 1_000_000 +
        (it.getOrElse(1) { 0 }) * 10_000 +
        (it.getOrElse(2) { 0 }) * 100 +
        (it.getOrElse(3) { 0 })
}
// Where the in-app updater looks for releases.
val updateRepo: String = (findProperty("updateRepo") as String?) ?: "NosoksNimbus/tg-ws-proxy-android"

android {
    namespace = "org.flowseal.tgwsproxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.flowseal.tgwsproxy"
        minSdk = 24
        targetSdk = 34
        versionName = appVersion
        versionCode = appVersionCode
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")

        ndk { abiFilters += abis }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        // Compose compiler 1.5.14 is the release that matches Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // In-app updates only work if every release is signed with the SAME key.
    // CI provides it through env vars (see .github/workflows/android.yml);
    // without them we fall back to the local debug key.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null && file(releaseKeystore).exists()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose UI ("liquid glass" interface)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
}
