# kotlinx.serialization — keep generated serializers of app models.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.nanote.**$$serializer { *; }
-keepclassmembers class com.nanote.** {
    *** Companion;
}
-keepclasseswithmembers class com.nanote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep enum values() / valueOf() for enums used with Room/JSON (PenType, Paper ids).
-keepclassmembers enum com.nanote.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Hilt / Room / Compose ship consumer rules of their own; nothing extra needed.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, SourceFile, LineNumberTable
