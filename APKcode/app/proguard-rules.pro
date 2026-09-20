# R8 keep rules for the release build.
#
# Only the things R8 cannot see are kept. Everything else — Compose, Retrofit,
# OkHttp, Coil, the app's own UI — ships with its own consumer rules or is
# reachable statically, so it is left to be shrunk and optimized normally.

# --- sherpa-onnx (bundled .aar, JNI) ---------------------------------------
# These classes are constructed and their fields read from native code, so R8
# sees no Java/Kotlin reference to them and would otherwise rename or strip
# them. Keeping members as well as the class is required: the JNI layer looks
# fields up by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- kotlinx.serialization --------------------------------------------------
# The compiler plugin generates a `Companion.serializer()` / `$$serializer` for
# every @Serializable class and reaches them reflectively by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.novelscraper.app.data.**$$serializer { *; }
-keepclassmembers class com.novelscraper.app.data.** {
    *** Companion;
}

# --- Retrofit ---------------------------------------------------------------
# The Api interface is implemented by a reflective proxy, and Retrofit reads the
# generic signatures and annotations off its methods to build each call.
-keep,allowobfuscation interface com.novelscraper.app.net.Api
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# --- WebView bridges --------------------------------------------------------
# The scrape relay and the NovelUpdates resolver drive offscreen WebViews; any
# @JavascriptInterface member is called from JS by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep source line numbers so a release stack trace is still readable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
