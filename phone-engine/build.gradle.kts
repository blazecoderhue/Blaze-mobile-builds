// Blaze Phone engine: vendored NewBlackbox (Apache-2.0, see LICENSE, NOTICE, UPSTREAM.md and CHANGES.md).
// An isolated Android library: only app/.../phone (BlazePhone) talks to it, and it only initialises in its own
// processes (":black" + ":p0".."p3"), never in Blaze's main process.
plugins { id("com.android.library") }

// The native hook library needs the NDK (CI installs it). -PphoneEngine.native=false skips it (local JVM builds and
// unit tests on hosts without an NDK); the Java side still compiles.
val nativeOn = (findProperty("phoneEngine.native") as String?)?.toBoolean() ?: true

android {
    namespace = "top.niunaijun.blackbox"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        if (nativeOn) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { aidl = true; buildConfig = false }
    aidlPackagedList += listOf("android/app/IServiceConnection.aidl", "android/accounts/IAccountManagerResponse.aidl")
    if (nativeOn) {
        externalNativeBuild { ndkBuild { path = file("src/main/cpp/Android.mk") } }
        ndkVersion = (findProperty("phoneEngine.ndk") as String?) ?: "28.2.13676358"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-unchecked", "-Xlint:-rawtypes", "-nowarn"))
}

dependencies {
    implementation(project(":phone-engine:black-reflection"))
    annotationProcessor(project(":phone-engine:compiler"))
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
    // FreeReflection (MIT, tiann): lifts the hidden-API restrictions the engine's reflection needs.
    implementation("com.github.tiann:FreeReflection:3.2.2")
}
