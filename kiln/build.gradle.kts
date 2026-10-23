// Blaze Kiln: clean-room app virtualization (ENGINE_DESIGN.md). Android library; the pure packages (manifest, apk,
// store, route) have no android.* imports and are tested as plain JVM unit tests with TestNG (Apache-2.0, ENGINE_DEPS.md).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blaze.kiln"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useTestNG()
            // TestGuestApkTest inspects the real test guest APK built in the same run; SlotManifestTest reads the
            // source manifest Android merges into the kilnTest build.
            it.dependsOn(":kiln-testguest:assembleDebug")
            it.systemProperty("kiln.testGuestApk", rootProject.file("kiln-testguest/build/outputs/apk/debug/kiln-testguest-debug.apk").path)
            it.systemProperty("kiln.manifest", file("src/main/AndroidManifest.xml").path)
            it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

dependencies {
    // Non-SDK access on API 28+ (runtime/HiddenApi only). Apache-2.0, ENGINE_DEPS.md.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Test only. Every Kiln dependency must be Apache-2.0/MIT/BSD (ENGINE_DEPS.md).
    testImplementation("org.testng:testng:7.10.2")
}
