// Blaze Engine: clean-room app virtualization (ENGINE_DESIGN.md). Android library; the pure packages (manifest, apk,
// store, route) have no android.* imports and are tested as plain JVM unit tests with TestNG (Apache-2.0, ENGINE_DEPS.md).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blaze.engine"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.useTestNG() }
    }
}

dependencies {
    // Test only. Every engine dependency must be Apache-2.0/MIT/BSD (ENGINE_DEPS.md).
    testImplementation("org.testng:testng:7.10.2")
}
