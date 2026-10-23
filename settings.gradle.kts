pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // v0.29.2: Termux terminal-emulator + terminal-view (Apache-2.0) for Blaze Terminal.
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app"); includeGroup("com.github.tiann") } }
    }
}
rootProject.name = "Blaze"
include(":app")
// v0.30.1 Blaze Music / Video Stores: the source interface, and the optional YouTube plug-in (only when its folder is here).
include(":storeapi")
// Blaze Phone (spike): the vendored app-virtualization engine (NewBlackbox, Apache-2.0) and its reflection codegen.
include(":phone-engine", ":phone-engine:black-reflection", ":phone-engine:compiler")
if (file("youtube/build.gradle.kts").exists()) include(":youtube")
