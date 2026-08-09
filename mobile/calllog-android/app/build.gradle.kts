import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildTime = OffsetDateTime.now(ZoneOffset.UTC)
val buildTimeText = buildTime.toString()
val generatedVersionCode =
    1_000_000_000 +
        (buildTime.year % 100) * 10_000_000 +
        buildTime.dayOfYear * 10_000 +
        buildTime.hour * 100 +
        buildTime.minute
val appVersionCode = providers.gradleProperty("playVersionCode")
    .orNull
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: generatedVersionCode
val appVersionName = providers.gradleProperty("playVersionName")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: "0.3.$appVersionCode"
val fixedDebugKeystoreBase64File = rootProject.file("debug-signing/callreport-debug.keystore.base64")
val fixedDebugKeystoreFile = rootProject.layout.buildDirectory
    .file("generated-signing/callreport-debug.keystore")
    .get()
    .asFile
val playSigningFile = rootProject.file("play-signing.properties")
val playSigning = Properties().apply {
    if (playSigningFile.isFile) {
        playSigningFile.inputStream().use { input -> load(input) }
    }
}
val hasPlaySigning = listOf("storeFile", "storeSecret", "keyAlias", "keySecret")
    .all { key -> playSigning.getProperty(key).orEmpty().isNotBlank() }

fun ensureFixedDebugKeystore(): File {
    require(fixedDebugKeystoreBase64File.isFile) {
        "Missing fixed debug keystore: ${fixedDebugKeystoreBase64File.path}"
    }
    fixedDebugKeystoreFile.parentFile.mkdirs()
    fixedDebugKeystoreFile.writeBytes(
        Base64.getMimeDecoder().decode(fixedDebugKeystoreBase64File.readText().trim())
    )
    return fixedDebugKeystoreFile
}

android {
    namespace = "com.onlineimoti.calllog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onlineimoti.relationshipmanager"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += setOf("bg")

        buildConfigField("String", "BUILD_TIME", "\"$buildTimeText\"")
        // Create this non-consumable one-time product in Play Console with the exact same ID.
        buildConfigField("String", "PLAY_COMPANY_LICENSE_PRODUCT_ID", "\"rm_company_license\"")
        buildConfigField("boolean", "PLAY_BILLING_ENABLED", "true")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = ensureFixedDebugKeystore()
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("playRelease") {
            if (hasPlaySigning) {
                storeFile = rootProject.file(playSigning.getProperty("storeFile"))
                storePassword = playSigning.getProperty("storeSecret")
                keyAlias = playSigning.getProperty("keyAlias")
                keyPassword = playSigning.getProperty("keySecret")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            if (hasPlaySigning) signingConfig = signingConfigs.getByName("playRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = when (buildType.name) {
                "release" -> "relationship-manager-release.apk"
                "debug" -> "relationship-manager.apk"
                else -> "relationship-manager-${buildType.name}.apk"
            }
        }
    }

    lint {
        lintConfig = file("lint.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.31")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
}
