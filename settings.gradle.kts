pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// downloads a JDK a build asks for and the computer lacks (the desktop app runs on Java 25)
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "music"
include(":app", ":shared", ":desktop")
