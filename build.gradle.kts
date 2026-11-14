plugins {
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}
