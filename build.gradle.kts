plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
// Optional external outputs avoid Windows/Dropbox locking generated native libraries.
allprojects {
    providers.gradleProperty("notes.buildRoot").orNull?.let { root ->
        layout.buildDirectory.set(file("$root/${if (path == ":") "root" else name}"))
    }
}
