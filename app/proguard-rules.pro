# A56 Kamera ProGuard kuralları
-keep class com.a56camera.** { *; }
-keep class android.hardware.camera2.** { *; }

# LiteRT / TFLite / GPU delegate
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn org.tensorflow.lite.**
-dontwarn org.jetbrains.annotations.**
