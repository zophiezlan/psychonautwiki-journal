# Preserve the original source file names and line numbers in the obfuscated
# bytecode. Without these attributes, user-submitted crash logs (or those
# captured via the optional crash reporter) cannot be mapped back to source —
# which makes harm-reduction-critical bug triage effectively impossible.
# Releases must publish the generated build/outputs/mapping/release/mapping.txt
# alongside the binary so external bug reports can be deobfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep generic signatures and runtime annotations for kotlinx.serialization
# and Koin reflection-driven binding.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations,AnnotationDefault

-keep public class com.isaakhanimann.journal.desktop.MainKt {
    public static void main(java.lang.String[]);
}

-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

-keep class kotlin.reflect.** { *; }
-keep class org.jetbrains.compose.** { *; }
-keep class androidx.compose.** { *; }

# kotlinx.serialization needs the @Serializable companion classes intact.
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}