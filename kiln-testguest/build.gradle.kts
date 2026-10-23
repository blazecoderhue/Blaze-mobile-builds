// Blaze Kiln M1 test guest: a tiny, self-contained Android app made by Blazeinchue only to be loaded INTO Kiln on a
// test phone (ENGINE_M1_PHONE_TEST.md). Plain android.app.Activity, Java, no libraries, no native code, one activity,
// so ApkInspector rates it M1 and its licence is ours. Sources: developer.android.com (Activity, resources, files).
// Never published on its own; the kilnTest build of :app bundles its debug APK as an asset.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.blaze.kiln.testguest"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.blaze.kiln.testguest"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-m1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
