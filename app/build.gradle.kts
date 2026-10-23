import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.blaze.assistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.blaze.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 164
        versionName = "0.45.19"
        // v0.30.1 Music Store: Jamendo client_id, from the environment or -Pjamendo.clientId at build time. Never hard-coded;
        // empty = "not configured" (the user can paste one in the Music Store instead).
        buildConfigField("String", "JAMENDO_CLIENT_ID", "\"" + (System.getenv("JAMENDO_CLIENT_ID") ?: (project.findProperty("jamendo.clientId") as String?) ?: "").replace("\"", "") + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    // 0.45.15 (key rotation, user-approved 22 Oct 08:32): no signing key or password lives in this repo. Gradle signs with the
    // SDK's own debug key (fine for local runs and CI test builds); a release APK is re-signed outside the repo with the
    // private Blaze release key and a v3 lineage from the old (burned, public) key, so existing installs still update.
    buildTypes {
        // Release = what you install: R8-optimised, not debuggable (Compose runs far smoother).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // The Needle runner ships as an executable named lib*.so so Android unpacks it into nativeLibraryDir.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libneedle.so", "**/libbusybox.so", "**/libproot.so", "**/libproot_loader.so", "**/libproot_loader32.so", "**/libtalloc.so", "**/libandroid-shmem.so", "**/libwgproxy.so", "**/libblazerouter.so")
        }
    }
    // v0.45.0 (#34): lint fails on NEW errors only; existing ones live in app/lint-baseline.xml (written by the first run).
    lint { checkReleaseBuilds = false; abortOnError = true; baseline = file("lint-baseline.xml") }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric screenshot tests (ui/ShotsTest) need the app's resources (fonts) and only run with BLAZE_SHOTS=1.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("blaze.shots", System.getenv("BLAZE_SHOTS") ?: "0")
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            // v0.45.0 (#28): Robolectric ships no Conscrypt JNI for linux-aarch64 (it only turns Conscrypt off by itself on
            // arm Macs), so on ARM Linux boxes every Robolectric test died with UnsatisfiedLinkError. Off there; x86 CI unchanged.
            if (System.getProperty("os.arch").orEmpty().lowercase().let { a -> a.contains("aarch64") || a.contains("arm64") })
                it.systemProperty("robolectric.conscryptMode", "OFF")
            // The compose test rule needs ui-test-manifest, which only the debug build has.
            if (it.name.contains("Release")) it.exclude("**/ShotsTest*")
            // A failing test prints its real message and causes in the CI log (not just "SQLException at Foo.kt:143").
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showCauses = true
                showStackTraces = true
            }
        }
    }
}

// ---------------------------------------------------------------- v0.28.4 blaze-docs
// Blaze's knowledge about itself, generated from this repo at build time into assets/blaze-docs/ (README, CHANGELOG split
// per version, STATUS, docs/, the hand-written app/blaze-docs/about.md with the build's version, commit and SDK levels, and
// a manifest). On the first launch after an update the app chunks it into blaze.db (blaze_docs + blaze_docs_fts) and the
// model looks it up with blaze_docs_search / blaze_docs_read; none of it goes into the prompt. Secrets, account IDs and
// e-mail addresses are scrubbed here and again when indexing (agent/BlazeDocs.kt, BlazeDocsTest).
abstract class BlazeDocsTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:Input abstract val compileSdk: Property<Int>
    @get:Input abstract val commit: Property<String>
    @get:Input abstract val built: Property<String>
    @get:Internal abstract val repoDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun scrub(s: String): String {
        var t = s
        val rules = listOf(
            Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "[email]",
            Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b|\bgithub_pat_[A-Za-z0-9_]{20,}\b""") to "[token]",
            Regex("""\bsk-[A-Za-z0-9_\-]{16,}\b|\bAIza[0-9A-Za-z_\-]{30,}\b|\bxox[abprs]-[A-Za-z0-9\-]{10,}\b""") to "[key]",
            Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\b""") to "[token]",
            Regex("""\b\d{6,}-[a-z0-9]{20,}\.apps\.googleusercontent\.com\b""") to "[client-id]",
            Regex("""\b[a-z][a-z0-9\-]{2,24}-\d{6}\b""") to "[project-id]",
            Regex("""\b[0-9a-fA-F]{32,}\b""") to "[id]",
            Regex("""(?i)\b(store|key)Password\s*=\s*"[^"]*"""") to "$1Password = \"[redacted]\"",
        )
        for ((re, rep) in rules) t = re.replace(t, rep)
        return t
    }

    @TaskAction fun run() {
        val root = repoDir.get().asFile
        val out = outputDir.get().asFile.resolve("blaze-docs")
        out.deleteRecursively(); out.mkdirs()
        val files = LinkedHashMap<String, String>()
        fun put(name: String, text: String) { if (text.isNotBlank()) files[name] = scrub(text.replace("\r\n", "\n")) }
        val about = root.resolve("app/blaze-docs/about.md")
        if (about.isFile) put("about.md", about.readText()
            .replace("{{VERSION}}", versionName.get()).replace("{{VERSION_CODE}}", versionCode.get().toString())
            .replace("{{COMMIT}}", commit.get()).replace("{{BUILT}}", built.get())
            .replace("{{MIN_SDK}}", minSdk.get().toString()).replace("{{TARGET_SDK}}", targetSdk.get().toString())
            .replace("{{COMPILE_SDK}}", compileSdk.get().toString()))
        root.resolve("README.md").takeIf { it.isFile }?.let { put("readme.md", it.readText()) }
        root.resolve("STATUS.md").takeIf { it.isFile }?.let { put("status.md", it.readText()) }
        // CHANGELOG: one file per version ("## v0.28.1: …"), plus an index of every version, newest first.
        root.resolve("CHANGELOG.md").takeIf { it.isFile }?.let { f ->
            val index = StringBuilder("# Changelog: every version, newest first\n\n")
            var head: String? = null; val body = StringBuilder(); val used = HashSet<String>()
            fun flush() {
                val h = head ?: return
                val ver = Regex("""^##\s+(v?\d[0-9A-Za-z.\-]*)""").find(h)?.groupValues?.get(1)?.trimEnd('.', ':') ?: "entry"
                var slug = ver.lowercase().let { if (it.startsWith("v") || it == "entry") it else "v$it" }
                var n = 2; val base = slug
                while (!used.add(slug)) slug = "$base-${n++}"
                put("changelog/$slug.md", "# Changelog " + h.removePrefix("##").trim() + "\n" + body.toString())
                index.append("- ").append(h.removePrefix("##").trim()).append('\n')
            }
            for (line in f.readText().replace("\r\n", "\n").lines()) {
                if (line.startsWith("## ")) { flush(); head = line; body.setLength(0) } else if (head != null) body.append(line).append('\n')
            }
            flush()
            put("changelog/index.md", index.toString())
        }
        root.resolve("docs").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }?.sortedBy { it.path }?.forEach {
            put("docs/" + it.relativeTo(root.resolve("docs")).invariantSeparatorsPath, it.readText())
        }
        val md = MessageDigest.getInstance("SHA-1")
        for ((n, t) in files) {
            val f = out.resolve(n); f.parentFile.mkdirs(); f.writeText(t)
            md.update(n.toByteArray()); md.update(0); md.update(t.toByteArray()); md.update(1)
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        out.resolve("manifest.json").writeText("{\n  \"version\": ${q(versionName.get())},\n  \"versionCode\": ${versionCode.get()},\n" +
            "  \"commit\": ${q(commit.get())},\n  \"built\": ${q(built.get())},\n  \"hash\": ${q(hash)},\n" +
            "  \"files\": [" + files.keys.joinToString(", ") { q(it) } + "]\n}\n")
    }
}

val repoRoot: File = rootProject.projectDir
val gitOut: (List<String>) -> String = { args ->
    try {
        val p = ProcessBuilder(listOf("git") + args).directory(repoRoot).redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0) s.lines().lastOrNull().orEmpty().trim() else ""
    } catch (_: Exception) { "" }
}
val docVersionName = android.defaultConfig.versionName ?: "unknown"
val docVersionCode = android.defaultConfig.versionCode ?: 0
val docMinSdk = android.defaultConfig.minSdk ?: 0
val docTargetSdk = android.defaultConfig.targetSdk ?: 0
val docCompileSdk = android.compileSdk ?: 0
val docCommit = (System.getenv("GITHUB_SHA") ?: "").ifBlank { gitOut(listOf("rev-parse", "HEAD")) }.take(12).ifBlank { "unknown" }
val docBuilt = gitOut(listOf("log", "-1", "--format=%cs")).ifBlank { "unknown" }
val docSources = listOf(rootProject.file("README.md"), rootProject.file("CHANGELOG.md"), rootProject.file("STATUS.md"),
    rootProject.fileTree("docs") { include("**/*.md") }, file("blaze-docs"))
val repoDirectory = rootProject.layout.projectDirectory
val fromRepo: BlazeDocsTask.() -> Unit = {
    sources.from(docSources)
    versionName.set(docVersionName)
    versionCode.set(docVersionCode)
    minSdk.set(docMinSdk)
    targetSdk.set(docTargetSdk)
    compileSdk.set(docCompileSdk)
    commit.set(docCommit)
    built.set(docBuilt)
    repoDir.set(repoDirectory)
}

// One generator per variant, wired as a generated assets folder (AGP owns its output path).
androidComponents {
    onVariants { v ->
        val t = tasks.register<BlazeDocsTask>("generate" + v.name.replaceFirstChar { it.uppercase() } + "BlazeDocs") { fromRepo() }
        v.sources.assets?.addGeneratedSourceDirectory(t, BlazeDocsTask::outputDir)
    }
}

// BlazeDocsTest reads the generated docs exactly as they ship (same generator, its own output folder).
val blazeDocsForTests = tasks.register<BlazeDocsTask>("generateBlazeDocsForTests") {
    fromRepo()
    outputDir.set(layout.buildDirectory.dir("generated/blazeDocsTest"))
}
tasks.withType<Test>().configureEach {
    dependsOn(blazeDocsForTests)
    systemProperty("blaze.docs.dir", layout.buildDirectory.dir("generated/blazeDocsTest/blaze-docs").get().asFile.path)
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR scan for Blaze Desktop pairing
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")   // browser VPN proxy, private-tab profiles, location-mask scripts
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("org.tukaani:xz:1.10")   // v0.29.2 .tar.xz rootfs for Ubuntu/Debian/Arch/Fedora   // v0.29.2 Blaze Terminal (pulls terminal-emulator + libtermux.so)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")   // v0.24.6: rounded icon buttons (browser, pointer menu, overlay)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")   // one mind: idle memory review (MindReviewWorker)
    // v0.26.3 one memory store: blaze.db (Desktop / CLI schema v1, FTS5) on a bundled modern SQLite, the same on every Android.
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // v0.28.x Blaze Camera: CameraX (works with no camera app, on low-end phones), ML Kit barcode scanning (bundled model,
    // offline), and Google's document scanner (Play services; used only when present, else a framed photo).
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")   // v0.24.7 Magic Hand: circle to grab, on-device OCR (bundled model, no network)
    implementation("com.alphacephei:vosk-android:0.3.47") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // v0.28.0 My voice: bzip2 for the downloaded PocketTTS model archive (pure Java; R8 keeps only the bzip2 reader).
    // The sherpa-onnx runtime itself is NOT a dependency: its Kotlin API is vendored (com.k2fsa.sherpa.onnx) and the
    // native library is downloaded on demand, so the APK only grows by a few hundred KB.
    implementation("org.apache.commons:commons-compress:1.21")
    // v0.30.1 Blaze Music / Blaze Video: media3 (ExoPlayer, MediaSession, PlayerView), Apache-2.0.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")   // v0.33.0 Clip Studio
    implementation("androidx.media3:media3-effect:$media3")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")   // v0.33.0 Offline Brain
    implementation(project(":storeapi"))
    // The YouTube Store is a plug-in: compiled in only when the youtube/ folder (Gradle :youtube) exists.
    if (rootProject.findProject(":youtube") != null) implementation(project(":youtube"))
    // v0.36.0 BlazeOS native apps: Chicory, a pure-JVM WebAssembly interpreter (no NDK, no per-CPU libraries).
    implementation("com.dylibso.chicory:runtime:1.7.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.hamcrest:hamcrest-core:1.3")   // 0.45.18: was a root hamcrest.jar; now a normal test dependency
    testImplementation("org.json:json:20240303")   // the real org.json (android.jar only has stubs)
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")   // v0.26.3: blaze.db tests on a JVM SQLite with FTS5 (store/JdbcSql)
    // Screenshot tests: Compose UI test on Robolectric's native graphics.
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
