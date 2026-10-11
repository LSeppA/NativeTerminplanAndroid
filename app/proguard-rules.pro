# App-specific R8 rules belong here.

# Firebase discovers ML Kit component registrars by class name and creates them
# through reflection. Its bundled rule keeps the class name but, with current
# R8 full mode, does not retain the required no-argument constructor.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
