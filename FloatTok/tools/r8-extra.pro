# Extra R8 rules for the offline build (Gradle builds get the equivalent from proguard-android-optimize.txt).
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
