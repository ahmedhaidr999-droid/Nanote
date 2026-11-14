# Pdfium (JNI)
-keep class com.shockwave.pdfium.** { *; }

# PDFBox
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.fontbox.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.nanote.app.**$$serializer { *; }
-keepclassmembers class com.nanote.app.** { *** Companion; }
-keepclasseswithmembers class com.nanote.app.** { kotlinx.serialization.KSerializer serializer(...); }
