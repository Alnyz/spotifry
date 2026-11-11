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

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

compose.desktop { application { mainClass = "com.spotifry.desktop.MainKt" } }
