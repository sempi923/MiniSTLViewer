# Optimización agresiva de R8
-optimizationpasses 5
-allowaccessmodification

# Preservar clases nativas de OpenGL y NIO Buffers
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class android.opengl.** { *; }
-dontwarn android.opengl.**

# Eliminar logs en producción
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
