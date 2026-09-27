-keepclasseswithmembernames class * {
    native <methods>;
}

# The engine hands this callback to native code, which looks its methods up by name, so the
# whole interface is kept rather than one signature: a minified build otherwise failed on the
# first tool call with 'no non-static method onToolCall'.
-keep interface dev.localassistant.llm.LlamaCppEngine$NativeCallback { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# sherpa-onnx reads its configuration objects field by field from native code, looking each one
# up by name, so a minified build aborts the process with "fid == null" the moment it builds a
# recognizer, a voice or the speech detector.
-keep class com.k2fsa.sherpa.onnx.** { *; }
