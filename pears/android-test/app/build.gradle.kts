// Step 1 test app: the engine bundle in a bare-kit worklet. Its own package id, so it never replaces the main app.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.beachsync.pearstest"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.beachsync.pearstest"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // both test phones; keeps the APK small. Needs no NDK.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDir("src/main/addons")
    packaging { jniLibs { useLegacyPackaging = false } }
}

// The engine bundle and its native add-ons, made from pears/ before every build.
val pears = rootDir.parentFile
val packEngine by tasks.registering(Exec::class) {
    workingDir = pears
    commandLine("nice", "node_modules/.bin/bare-pack", "--preset", "android",
        "--out", "android-test/app/src/main/assets/engine.bundle", "android-test/js/worklet.js")
}
val linkAddons by tasks.registering(Exec::class) {
    workingDir = pears
    commandLine("nice", "node_modules/.bin/bare-link", "--host", "android-arm64",
        "--out", "android-test/app/src/main/addons", "android-test/js/worklet.js")
}
tasks.named("preBuild") { dependsOn(packEngine, linkAddons) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.github.holepunchto.bare-kit-kotlin:bare-kit-kotlin-android:v0.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
