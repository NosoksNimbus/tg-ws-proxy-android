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

// Version is read from proxy/__init__.py so Android follows the desktop release.
val coreVersion: String = Regex("""__version__\s*=\s*"([^"]+)"""")
    .find(rootProject.file("../proxy/__init__.py").readText())
    ?.groupValues?.get(1) ?: "0.0.0"

android {
    namespace = "org.flowseal.tgwsproxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.flowseal.tgwsproxy"
        minSdk = 24
        targetSdk = 34
        versionName = coreVersion
        versionCode = coreVersion.split(".").let {
            (it.getOrNull(0)?.toIntOrNull() ?: 0) * 10000 +
                (it.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
                (it.getOrNull(2)?.toIntOrNull() ?: 0)
        }

        ndk {
            // Python 3.12 in Chaquopy is 64-bit only, so no armeabi-v7a.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No private keystore in the repo: sign with the debug key so the
            // APK is directly installable. Replace for Play Store publishing.
            signingConfig = signingConfigs.getByName("debug")
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
            install("cryptography")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
