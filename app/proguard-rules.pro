# Names stay readable, so crash reports from the release build still make sense
-dontobfuscate

# NewPipe reads YouTube with reflection and runs YouTube's player script in Rhino
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.ietf.jgss.**
-dontwarn jdk.dynalink.**
