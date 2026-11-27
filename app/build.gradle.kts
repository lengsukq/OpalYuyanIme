plugins {
    id("com.android.application")
}

kotlin {
    jvmToolchain(21)
}

val resolvedSdkDir = (rootProject.extra["opalDeskAndroidSdkDir"] as? String).orEmpty()
val hasAndroid37Minor2 = resolvedSdkDir.isNotBlank() &&
    file("$resolvedSdkDir/platforms/android-37.2/android.jar").isFile

@Suppress("UnstableApiUsage")
android {
    namespace = "io.github.lengsukq.opaldesk.ime"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = if (hasAndroid37Minor2) 2 else 0
        }
    }

    defaultConfig {
        applicationId = "io.github.lengsukq.opaldesk.ime"
        minSdk = 30
        targetSdk = 37
        versionCode = 10020
        versionName = "1.0.20"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.clear()
            java.directories.add("src")
            kotlin.directories.clear()
            kotlin.directories.add("src")
            res.directories.clear()
            res.directories.add("res")
            jniLibs.directories.clear()
            jniLibs.directories.add("jniLibs")
            assets.directories.clear()
            assets.directories.add("assets")
        }
        named("test") {
            java.directories.clear()
            java.directories.add("test")
            kotlin.directories.clear()
            kotlin.directories.add("test")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Sign with the exact same root signing certificate as :helper so HelperClientBridge X.509
    // SHA-256 verification automatically trusts this optional companion plugin APK.
    val activeKsPath = (rootProject.extra["opalDeskSigningKeystorePath"] as? String).orEmpty()
    val activeKs = activeKsPath.takeIf { it.isNotBlank() }?.let { file(it) }
    val signingPass = (rootProject.extra["opalDeskSigningPassword"] as? String).orEmpty()
    val signingAlias = (rootProject.extra["opalDeskSigningAlias"] as? String).orEmpty().ifBlank { "opaldesk" }

    val requireReleaseKey = (System.getenv("OPALDESK_REQUIRE_RELEASE_KEY") == "1")
        || (project.findProperty("requireReleaseKey")?.toString()?.equals("true", ignoreCase = true) == true)
        || (System.getenv("GITHUB_REF")?.startsWith("refs/tags/v") == true)
    val allowUnsignedRelease = !requireReleaseKey && (
        (System.getenv("OPALDESK_ALLOW_UNSIGNED_RELEASE") == "1")
            || (project.findProperty("allowUnsignedRelease")?.toString()?.equals("true", ignoreCase = true) == true)
        )
    val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

    if ((requireReleaseKey || (isReleaseTaskRequested && !allowUnsignedRelease)) && activeKs == null) {
        throw GradleException(
            "Release build requires OPALDESK_KEYSTORE (or local opaldesk.p12). " +
                "Run ./scripts/sync-signing-secrets.sh to copy opaldesk.p12 from ../OpalDesk, or for keyless CI " +
                "verification pass -PallowUnsignedRelease=true (or OPALDESK_ALLOW_UNSIGNED_RELEASE=1)."
        )
    }

    signingConfigs {
        if (activeKs != null) {
            create("extrelease") {
                storeFile = activeKs
                storeType = "PKCS12"
                storePassword = signingPass
                keyAlias = signingAlias
                keyPassword = signingPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (activeKs != null) {
                signingConfigs.getByName("extrelease")
            } else {
                null
            }
        }
        debug {
            if (activeKs != null) {
                signingConfig = signingConfigs.getByName("extrelease")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
