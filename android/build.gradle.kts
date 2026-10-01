plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}

// Optional: XVID_BUILD_DIR puts build outputs outside the project, e.g. when the project
// is in a synced folder (OneDrive) that locks or replaces files in the middle of a build.
providers.environmentVariable("XVID_BUILD_DIR").orNull?.takeIf { it.isNotBlank() }?.let { dir ->
    allprojects { layout.buildDirectory.set(file("$dir/$name")) }
}
