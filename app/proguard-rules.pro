# Pixora ProGuard kuralları
-keep class io.github.marquesource.pixora.** { *; }
-keep class android.hardware.camera2.** { *; }

# LiteRT / TFLite / GPU delegate
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn org.tensorflow.lite.**
-dontwarn org.jetbrains.annotations.**

# Yayın sürümünde ayrıntılı günlükleri kaldır (uyarı ve hatalar kalır)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Kullanılmayan TFLite/OpenCV sınıflarına dair uyarılar
-dontwarn org.opencv.**
