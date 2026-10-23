pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // v0.29.2: Termux terminal-emulator + terminal-view (Apache-2.0) for Blaze Terminal.
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}
rootProject.name = "Blaze"
include(":app")
// v0.30.1 Blaze Music / Video Stores: the source interface, and the optional YouTube plug-in (only when its folder is here).
include(":storeapi")
if (file("youtube/build.gradle.kts").exists()) include(":youtube")
// Blaze Kiln (clean-room app virtualization, ENGINE_DESIGN.md). Not a dependency of :app yet.
include(":kiln")
