import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop app: a Compose window on top of :shared, playing through mpv.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose")
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.haze)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Android has org.json built in; a desktop JVM needs it and SQLite brought along
    implementation("org.json:json:20240303")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    // media keys and the desktop's player widget: MPRIS over D-Bus
    implementation("com.github.hypfvieh:dbus-java-core:5.2.2")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.spotifry.desktop.MainKt"
        nativeDistributions {
            // an app folder with its own Java inside; packaging/build-appimage.sh turns it into one AppImage file
            packageName = "spotifry"
            packageVersion = "0.2.0"
            // only what the app needs (from suggestRuntimeModules); jdk.crypto.ec keeps the HTTPS connections working
            modules("java.instrument", "java.net.http", "jdk.dynalink", "jdk.security.auth", "jdk.unsupported", "jdk.crypto.ec")
            linux { iconFile.set(project.file("packaging/spotifry.png")); appCategory = "AudioVideo" }
        }
    }
}
