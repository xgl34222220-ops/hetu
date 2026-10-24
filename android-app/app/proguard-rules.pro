# Hetu release (R8). Names are kept: crash reports, JNI and reflective libraries stay readable.
-dontobfuscate

# JNI: libhetu_core.so exports Java_io_github_xgl34222220_hetu_MihomoNative_invoke.
-keep class io.github.xgl34222220.hetu.MihomoNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# ViewModels created reflectively by ViewModelProvider.
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# Reflection-heavy libraries: YAML (SnakeYAML), JSON schema validation (networknt + Jackson),
# the code editor and SVG decoding. Kept whole; they are small next to Compose.
-keep class org.yaml.snakeyaml.** { *; }
-keep class com.networknt.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class io.github.rosemoe.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# Optional JVM-only dependencies of those libraries that Android does not ship.
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn com.ethlo.**
-dontwarn org.joda.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn com.google.re2j.**
-dontwarn org.jcodings.**
-dontwarn org.joni.**
-dontwarn kotlinx.serialization.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-ignorewarnings
