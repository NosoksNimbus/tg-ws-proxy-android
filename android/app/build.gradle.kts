plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// The proxy core lives in ../../proxy and is shared with the desktop builds.
// Copy it into the default Python source root so Chaquopy packages it as the
// `proxy` package. (Chaquopy's source-set extension is not visible from the
// Kotlin DSL, so adding a separate srcDir is not an option here.)
val syncProxyCore by tasks.registering(Sync::class) {
    from(rootProject.file("../proxy")) {
        exclude("__pycache__", "**/*.pyc")
    }
    into(file("src/main/python/proxy"))
}
tasks.named("preBuild") { dependsOn(syncProxyCore) }
// The Python source merge reads src/main/python, so it must run after the sync.
tasks.matching { it.name.contains("PythonSources") }.configureEach { dependsOn(syncProxyCore) }

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
val abis: List<String> = ((findProperty("abis") as String?) ?: "arm64-v8a,x86_64").split(",")

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

        ndk {
            // Python 3.12 in Chaquopy is 64-bit only, so no armeabi-v7a.
            // Release CI passes -Pabis=arm64-v8a to keep the APK (and updates) small.
            abiFilters += abis
        }
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

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("httpx==0.28.1")
            install("h2")
            install("certifi")
            // 42.0.8 is the newest version with prebuilt Chaquopy Android wheels;
            // newer releases only ship an sdist, which can't be cross-compiled here.
            install("cryptography==42.0.8")
            // chaquo.com is unreachable from some networks, so resolve the Android
            // wheels from the repo-local offline wheelhouse (arm64-v8a, CPython 3.12).
            options(
                "--no-index",
                "--find-links", rootProject.file("wheels").absolutePath,
            )
        }
    }
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
