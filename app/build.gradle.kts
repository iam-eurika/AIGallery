import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    // Must come last: it inspects the plugins above to wire up its annotation processor.
    alias(libs.plugins.objectbox)
}

// The app's version comes from the release tag (v1.2.0 -> versionName 1.2.0, versionCode 10200),
// so the in-app update check can compare it with GitHub's latest release. CI tag builds pass the
// tag via GITHUB_REF_NAME; local builds use the nearest git tag; -PappVersion overrides both.
val appVersion: String = run {
    val fromProp = providers.gradleProperty("appVersion").orNull
    val fromCi = providers.environmentVariable("GITHUB_REF_NAME").orNull?.takeIf { Regex("v\\d+(\\.\\d+)*").matches(it) }
    val fromGit = runCatching {
        providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().ifEmpty { null }
    }.getOrNull()
    (fromProp ?: fromCi ?: fromGit ?: "0.0.0").removePrefix("v")
}
val appVersionCode: Int = appVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10_000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }).coerceAtLeast(1) }

android {
    namespace = "com.lucrativeworm.seekframe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lucrativeworm.seekframe"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per ABI plus a universal one. LiteRT-LM only ships arm64-v8a and x86_64 natives,
    // so those are the only ABIs we can support (phones are arm64; emulators arm64/x86_64).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric needs merged resources; Android stubs return defaults instead of throwing.
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        noCompress += listOf("litertlm", "tflite", "task")
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

// The MagicTouch segmentation model (~30 MB) is fetched at build time and verified, rather than
// committed to git. Unlike EmbeddingGemma it ships in the APK, so circling works instantly.
val segmenterModel = layout.projectDirectory.file("src/main/assets/interactive_segmentation.task")
val downloadSegmenterModel by tasks.registering {
    val dest = segmenterModel.asFile
    outputs.file(dest)
    onlyIf { !dest.exists() }
    doLast {
        val url = "https://storage.googleapis.com/mediapipe-models/interactive_segmenter_v2/magic_touch/int8/latest/interactive_segmentation.task"
        val expected = "38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03"
        dest.parentFile.mkdirs()
        val tmp = File(dest.path + ".tmp")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (digest != expected) {
            tmp.delete()
            throw GradleException(
                "interactive_segmentation.task checksum mismatch ($digest). Google may have " +
                    "published a new 'latest' model; verify it and update the expected hash."
            )
        }
        tmp.renameTo(dest)
    }
}
tasks.named("preBuild") { dependsOn(downloadSegmenterModel) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // Deliberately no Material: the whole UI is built on foundation + our own design tokens.
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // Video: frames as thumbnails, and a player that can jump to where a search matched.
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)

    // Dependency injection: one app-wide graph of singletons, injected into the activity,
    // ViewModels and the background indexing worker.
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    kapt(libs.androidx.hilt.compiler)

    // EmbeddingGemma 2, two ways: Android's own copy through ML Kit GenAI (AICore devices), or
    // the bundled LiteRT-LM model through MediaPipe's UniversalEmbedder everywhere else.
    implementation(libs.mlkit.genai.embedding)
    implementation(libs.mediapipe.tasks.retrieval)
    implementation(libs.litertlm.android)
    // MagicTouch interactive segmenter: turns a circle/scribble into the mask of one object.
    implementation(libs.mediapipe.tasks.vision)
    // QR codes in photos: ML Kit's bundled (offline, no Play Services) barcode model.
    implementation(libs.mlkit.barcode.scanning)
    // Text in photos (long-press to select and copy): ML Kit's bundled offline Latin model.
    implementation(libs.mlkit.text.recognition)
    // Spoken words in videos: Android's own speech-to-text through ML Kit GenAI where the phone
    // offers it, and Vosk's offline recogniser as the fallback. Vosk's small English model is a
    // separate download the user agrees to; it calls into native code through JNA's Android build.
    implementation(libs.mlkit.genai.speech)
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
