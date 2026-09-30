// Plain Kotlin (JVM) module that holds the phone app's behaviour.
// No Android dependencies: everything the outside world provides sits behind
// the small interfaces in Ports.kt, so it can be tested with fakes.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
