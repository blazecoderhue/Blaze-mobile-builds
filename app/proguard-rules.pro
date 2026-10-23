-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.blaze.store.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep class rikka.shizuku.** { *; }
# Wireless ADB: libadb looks up Conscrypt by reflection, spake2 is JNI
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.bouncycastle.**
# On-device AI: LiteRT-LM is driven from JNI and reads its config through reflection
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
