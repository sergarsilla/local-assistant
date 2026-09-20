# Called from native code by name.
-keepclassmembers class * {
    boolean onBytes(byte[]);
}
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.google.ai.edge.litertlm.** { *; }
