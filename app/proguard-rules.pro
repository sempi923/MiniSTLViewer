# Optimización agresiva de R8
-optimizationpasses 5
-allowaccessmodification

# Preservar clases del modelo, geometría y parsers (STL y 3MF)
-keep class com.ministl.viewer.model.** { *; }
-keep class com.ministl.viewer.parser.** { *; }
-keep class com.ministl.viewer.util.** { *; }
-keep class com.rabbit3d.viewer.** { *; }

# Preservar XmlPullParser nativo para soporte de archivos .3MF
-keep interface org.xmlpull.v1.** { *; }
-keep class org.xmlpull.v1.** { *; }
-dontwarn org.xmlpull.v1.**

# Preservar clases nativas de OpenGL ES 2.0 (GLES20) y NIO Buffers
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class android.opengl.** { *; }
-keep class android.opengl.GLES20 { *; }
-keep class javax.microedition.khronos.** { *; }
-dontwarn android.opengl.**

# Eliminar logs de depuración en producción
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
