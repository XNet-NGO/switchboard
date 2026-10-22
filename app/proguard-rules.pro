# Keep the JNI bridge: native code calls bindSocketToNetwork by name, and the
# native method names must not be renamed.
-keep class org.xnet.switchboard.SwitchboardNative { *; }
