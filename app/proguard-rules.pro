# Vosk wake word runs through JNA, which finds classes by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# Services, receivers and the widget are referenced from the manifest (kept by default).
-keep class com.blaze.assistant.** extends android.app.Service { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.blaze.assistant.agent.MindReviewWorker { <init>(...); }
# v0.23.3: the Design view's JS bridge (BlazeDesign.gl / error / ready / fail) is called by name from the page.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class com.blaze.assistant.ui.DesignBridge { *; }
# v0.26.3 blaze.db: the bundled SQLite's native code throws androidx.sqlite.SQLiteException by class name.
-keep class androidx.sqlite.SQLiteException { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }
# v0.28.0 My voice: libsherpa-onnx-jni.so (downloaded, loaded by path) reads these classes' fields and calls their
# constructors/methods by name over JNI, so nothing in the package may be renamed or removed.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# commons-compress (bzip2 for the voice model archive) has optional codecs we never use.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
# v0.30.1 Blaze Music / Video: the download worker is created by WorkManager by class name.
-keep class com.blaze.assistant.mediaapps.MediaDownloadWorker { <init>(...); }

# v0.33.0 Offline Brain (MediaPipe LLM Inference): JNI + protobuf-lite annotations.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
