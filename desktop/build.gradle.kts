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
    // drag to reorder a playlist and the queue, the same library as the phone
    implementation(libs.reorderable)
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
        // the release build drops library code the app never uses (about 20 MB less); to turn it off: isEnabled.set(false)
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate.set(false)
        }
        nativeDistributions {
            // an app folder with its own Java inside; packaging/build-appimage.sh turns it into one AppImage file
            packageName = "spotifry"
            // the Windows installer and the macOS disk image; Linux is packed by packaging/build-appimage.sh from createDistributable
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageVersion = "1.1.1"
            // only what the app needs (from suggestRuntimeModules); jdk.crypto.ec keeps the HTTPS connections working
            modules("java.instrument", "java.net.http", "jdk.dynalink", "jdk.httpserver", "jdk.security.auth", "jdk.unsupported", "jdk.crypto.ec")
            // files that travel inside the installed app: mpv, ffmpeg and ffprobe, put in app-resources/windows (and app-resources/macos)
            // by the build recipe (.github/workflows/windows.yml and macos.yml) before packaging; nothing in the repo
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))
            linux { iconFile.set(project.file("packaging/spotifry.png")); appCategory = "AudioVideo" }
            windows {
                iconFile.set(project.file("packaging/spotifry.ico"))
                menuGroup = "Spotifry"; shortcut = true; perUserInstall = true; dirChooser = true
                upgradeUuid = "6e0f4f0e-3f0c-4a54-9a8b-7a1f2c5d9b31"
            }
            // no Apple developer account: the app is signed only by itself, so the first start needs "Open Anyway" in Privacy & Security
            macOS {
                iconFile.set(project.file("packaging/spotifry.icns"))
                packageName = "Spotifry"; dockName = "Spotifry"
                bundleID = "com.spotifry.desktop"
                appCategory = "public.app-category.music"
                minimumSystemVersion = "12.0"
            }
        }
    }
}

// the copy into the app drops the "may run" mark from mpv, ffmpeg and ffprobe, which macOS then refuses to start; the disk
// image is made from this app folder, so putting the mark back here covers both
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("**/resources/mpv", "**/resources/ffmpeg", "**/resources/ffprobe") }.forEach { it.setExecutable(true, false) }
    }
}
