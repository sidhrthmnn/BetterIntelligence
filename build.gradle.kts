// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
  dependencies {
    // LiteRT-LM 0.18 is compiled with Kotlin 2.4 metadata. Override AGP's
    // bundled 2.2 compiler using the supported built-in Kotlin mechanism.
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
  }
}
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}
