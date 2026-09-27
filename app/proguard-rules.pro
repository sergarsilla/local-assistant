# Called from native code by name.
-keepclassmembers class * {
    boolean onBytes(byte[]);
}
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.google.ai.edge.litertlm.** { *; }

# sherpa-onnx reads its configuration objects field by field from native code, looking each one
# up by name, so a minified build aborts the process with "fid == null" the moment it builds a
# recognizer, a voice or the speech detector.
-keep class com.k2fsa.sherpa.onnx.** { *; }
