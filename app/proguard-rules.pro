# Room, Firebase, WorkManager, and Kotlin serialization metadata are covered by
# their published consumer rules. App-specific compatibility rules follow.

# Lifecycle 2.8 resolves Compose UI 1.6's lifecycle owner through reflection.
# Preserve that compatibility bridge in optimized releases so it uses the same
# CompositionLocal that AndroidComposeView provides.
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static androidx.compose.runtime.ProvidableCompositionLocal getLocalLifecycleOwner();
}
