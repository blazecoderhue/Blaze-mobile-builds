import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.blaze.store"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.blaze.store"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Phones and TVs are ARM; the x86 builds of the native libraries only served emulators
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = System.getenv("BLAZE_STORE_PASSWORD") ?: keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = System.getenv("BLAZE_KEY_PASSWORD") ?: System.getenv("BLAZE_STORE_PASSWORD") ?: keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true; aidl = false }
    packaging {
        // Blaze Store ships as a direct download, so the download size wins over install-time extraction of the .so files
        jniLibs.useLegacyPackaging = true
        // BouncyCastle data for algorithms the store never touches (post-quantum Picnic tables, PKIX reviewer messages)
        // The on-device AI engine (about 10 MB) downloads the first time someone picks an on-device model, so it never ships in the APK
        jniLibs.excludes += listOf("lib/arm64-v8a/liblitertlm_jni.so", "lib/x86_64/liblitertlm_jni.so")
        resources.excludes += listOf("org/bouncycastle/pqc/**", "org/bouncycastle/x509/CertPathReviewerMessages*", "org/bouncycastle/pkix/CertPathReviewerMessages*")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-beta01")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Built-in wireless ADB: installs as the shell user, which skips the unverified-developer 24-hour wait
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    // Store AI on-device models (LiteRT-LM)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    testImplementation("junit:junit:4.13.2")
    // Screenshot harness (ShotsTest): ./gradlew :app:testDebugUnitTest --tests "*ShotsTest*" -Pshots
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
}

// conscrypt-android's JNI only exists on devices; under Robolectric it shadows the JVM Conscrypt Robolectric ships and every test fails to boot
configurations.configureEach { if (name.endsWith("UnitTestRuntimeClasspath")) exclude(group = "org.conscrypt", module = "conscrypt-android") }

tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", "true")
    systemProperty("blaze.shots", providers.gradleProperty("shots").getOrElse(""))
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}
