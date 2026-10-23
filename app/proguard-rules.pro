# SocialDL ProGuard Rules

# Keep Chaquopy
-keep class com.chaquo.python.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Hilt
-keep class dagger.hilt.** { *; }

# Keep data classes used for serialization
-keep class com.socialdl.src.core.model.** { *; }
