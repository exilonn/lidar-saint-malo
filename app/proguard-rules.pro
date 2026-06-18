# kotlinx.serialization keeps generated serializers; the plugin emits the needed rules,
# but keep DTOs explicitly in case of aggressive shrinking.
-keepclassmembers class com.exilon.tides.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.exilon.tides.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
