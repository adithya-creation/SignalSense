# Add project specific ProGuard rules here.

# Keep Gson serialization classes
-keepattributes Signature
-keepattributes *Annotation*

# Keep SwitchLogEntry for Gson serialization
-keep class com.signalsense.app.SwitchLogEntry { *; }

# Keep NetworkGeneration enum
-keep class com.signalsense.app.NetworkGeneration { *; }

# Gson specific rules
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer


