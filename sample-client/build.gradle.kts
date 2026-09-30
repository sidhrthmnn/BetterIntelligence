plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.example.betterclient"
    compileSdk = 36
    defaultConfig { applicationId = "com.example.betterclient"; minSdk = 24; targetSdk = 36 }
    buildFeatures { aidl = true }
    sourceSets.getByName("main").aidl.srcDir("../app/src/main/aidl")
}
