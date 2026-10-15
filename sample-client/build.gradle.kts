plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.example.betterclient"
    compileSdk = 36
    defaultConfig { applicationId = "com.example.betterclient"; minSdk = 24; targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true }
    sourceSets.getByName("main").aidl.srcDir("../app/src/main/aidl")
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
}
