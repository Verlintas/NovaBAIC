# Keep kotlinx.serialization generated serializers for our model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.verlintas.baic2.** {
    *** Companion;
}
-keepclasseswithmembers class com.verlintas.baic2.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.verlintas.baic2.**$$serializer { *; }
-keep class com.verlintas.baic2.core.model.** { *; }

# SnakeYAML references desktop java.beans introspection we never use.
-dontwarn java.beans.**
-keep class org.yaml.snakeyaml.** { *; }

# Loaded by name via app_process as the resident virtual-display helper.
-keep class com.verlintas.baic2.device.impl.display.DisplayHelperMain { *; }

# Wireless debugging: Conscrypt is selected by reflection in libadb, and the
# ADB/Spake2/BouncyCastle stacks are used through reflection or JCA lookups.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-keep class org.bouncycastle.** { *; }
