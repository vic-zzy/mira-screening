# Keep TFLite native bindings.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# Keep Compose runtime metadata used by previews.
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
