# Only unused library code goes: nothing is renamed or rewritten, so logs and crash traces stay readable
-dontobfuscate
-dontoptimize

# The app's own code stays whole (it is small, and Room and the media keys find parts of it by name)
-keep class com.spotifry.** { *; }

# NewPipe reads YouTube with reflection and runs YouTube's player script in Rhino; protobuf-lite reads its fields by name
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.google.protobuf.** { *; }

# media keys and the desktop's player widget: dbus-java builds its calls from interfaces and finds its transport by ServiceLoader
-keep class org.freedesktop.dbus.** { *; }
-keep interface * extends org.freedesktop.dbus.interfaces.DBusInterface { *; }
-keep class * implements org.freedesktop.dbus.interfaces.DBusInterface { *; }

# SQLite's native part calls back into these by name; Room finds the generated database by name
-keep class androidx.sqlite.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# found by ServiceLoader
-keep class org.slf4j.** { *; }
-keep class kotlinx.coroutines.swing.** { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# optional parts of libraries that this app never loads
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.ietf.jgss.**
-dontwarn jdk.dynalink.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn com.google.protobuf.**
-dontwarn org.freedesktop.dbus.**
-dontwarn kotlinx.serialization.**
-dontwarn org.jsoup.**
-dontwarn javax.annotation.**
-dontwarn org.graalvm.**
