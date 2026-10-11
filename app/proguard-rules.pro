# App-specific R8 rules belong here.

# Firebase discovers ML Kit component registrars by class name and creates them
# through reflection. Its bundled rule keeps the class name but, with current
# R8 full mode, does not retain the required no-argument constructor.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# Keep the official edge-to-edge compatibility entry point intact. If R8
# inlines it into MainActivity, Google Play sees its compatibility calls as
# direct app calls and reports unsupported system-bar color APIs.
-keep class androidx.core.view.WindowCompat {
    public static void enableEdgeToEdge(android.view.Window);
}
