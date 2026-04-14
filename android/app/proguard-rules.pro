# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.feedflow.app.**$$serializer { *; }
-keepclassmembers class com.feedflow.app.** { *** Companion; }
-keepclasseswithmembers class com.feedflow.app.** { kotlinx.serialization.KSerializer serializer(...); }
