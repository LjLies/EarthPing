# osmdroid uses reflection for some tile providers
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# No renaming: the app is open source, so renaming hides nothing, and readable names and line
# numbers make crash reports usable. Shrinking and optimization stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
