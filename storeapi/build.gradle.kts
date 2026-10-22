// v0.30.1: the Store source interface and model (pure Kotlin), shared by the app and optional source plug-ins (:youtube).
plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    testImplementation("junit:junit:4.13.2")
}
