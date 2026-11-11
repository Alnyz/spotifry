import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code both the Android app and the desktop app use. Plain Kotlin, no Android.
// the Kotlin plugin is already on the build classpath (buildSrc), so it is applied without a version
plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    api(libs.newpipe)
    api(libs.okhttp)
    api(libs.coroutines.core)
    // org.json is built into Android, so it must not ship in the app; the desktop app adds it to its own run
    compileOnly("org.json:json:20240303")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
