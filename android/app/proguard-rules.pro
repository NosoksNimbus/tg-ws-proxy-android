# --- JNI ---------------------------------------------------------------------------------
# libtgws_core.so looks its entry points up as Java_org_flowseal_tgwsproxy_RustEngine_n*,
# so both the class name and the native method names must survive shrinking.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# --- WARP backend ------------------------------------------------------------------------
# WarpManager builds the AmneziaWG backend through reflection (GoBackend, TunnelActionHandler,
# Tunnel), and the Go side calls back into these classes by name, so keep them whole.
-keep class org.amnezia.** { *; }
-keep interface org.amnezia.** { *; }
-dontwarn org.amnezia.**

# --- Misc --------------------------------------------------------------------------------
# Kotlin metadata is needed for Compose reflection-free code paths but is harmless to keep;
# annotations and generic signatures come from the default rules already.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
