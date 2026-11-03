plugins { alias(libs.plugins.android.application) }
val isolated = providers.gradleProperty("biValidationPackage").orNull == "true"
val biPackage = "com.aistudio.betterintelligence.mobile" + if (isolated) ".validation" else ""
android {
    namespace = "com.example.betterclient"
    compileSdk = 36
    defaultConfig { applicationId = if (isolated) "com.example.betterclient.rustvalidation" else "com.example.betterclient"; minSdk = 24; targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["biPackage"] = biPackage
        buildConfigField("String", "BI_PACKAGE", "\"$biPackage\"")
    }
    buildFeatures { aidl = true; buildConfig = true }
    sourceSets.getByName("main").aidl.srcDir("../app/src/main/aidl")
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
}
