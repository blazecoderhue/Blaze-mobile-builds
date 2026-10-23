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
// Blaze Kiln (clean-room app virtualization, ENGINE_DESIGN.md). Only the kilnTest build type of :app depends on it.
include(":kiln")
// Kiln M1 test guest (tiny app of our own, bundled only into the kilnTest build of :app).
include(":kiln-testguest")
