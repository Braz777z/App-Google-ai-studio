# Keep WebView JavaScript bridge methods in Release builds
-keepclassmembers class com.example.ui.components.StudioPortalBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
