# WebView bridge methods must stay, or the page cannot call the app
-keepclassmembers class com.example.callsync.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.callsync.InstallResultReceiver { *; }
-keep class com.example.callsync.ApkInstaller { *; }
