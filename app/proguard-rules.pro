# The WebView bridge. The page calls these by name across the JavaScript
# interface, so R8 cannot rename the class or any of its methods — a bridge
# whose methods were obfuscated would throw on the first press of any button,
# and the failure would read as "the button does nothing" rather than as a
# missing method.
-keep class com.prf.security.ui.MainActivity$Bridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# The device admin receiver is named in AndroidManifest.xml by string, so the
# manifest and the class have to keep agreeing on its name after obfuscation.
-keep class com.prf.security.mdm.PrfDeviceAdminReceiver { *; }
-keep class com.prf.security.PRFApp { *; }

# Every service and receiver is likewise resolved by name from the manifest.
-keep class com.prf.security.screen.ScreenRecorderService { *; }
-keep class com.prf.security.mdm.PolicyWatchService { *; }
-keep class com.prf.security.mdm.BootReceiver { *; }

# kotlinx.serialization generates a companion `serializer()` per @Serializable
# class and looks it up reflectively, so the generated members must survive.
-keepclassmembers class * { @kotlinx.serialization.SerialName <fields>; }
-keep,includedescriptorclasses class com.prf.security.**$$serializer { *; }
-keepclassmembers class com.prf.security.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# The wire models keep their field names: the server stores the JSON keys, and
# an obfuscated field name would be a key the server has never heard of.
-keep class com.prf.security.data.** { *; }
