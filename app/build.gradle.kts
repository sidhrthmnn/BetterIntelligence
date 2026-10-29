import java.net.URI
import java.security.MessageDigest

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

// Keep the unverified device-specific SDK out of Play builds until the physical
// SM8750 validation checklist passes. This builds real QNN inference, not an
// emulator delegate: ./gradlew -PbiQualcommNpu=true :app:assembleDebug
val qualcommNpu = providers.gradleProperty("biQualcommNpu").map { it.toBooleanStrict() }.orElse(false)
val qnnRuntime by configurations.creating { isTransitive = false }
val qualcommOutput = layout.buildDirectory.dir("generated/qualcomm-npu")
val prepareQualcommNpu by tasks.registering {
  notCompatibleWithConfigurationCache("Optional vendor extraction uses Gradle project copy operations")
  inputs.property("enabled", qualcommNpu)
  inputs.files(qnnRuntime)
  inputs.file(rootProject.file("third_party/litert-samples-LICENSE"))
  outputs.dir(qualcommOutput)
  doLast {
    val output = qualcommOutput.get().asFile
    delete(output)
    if (qualcommNpu.get()) {
      val runtimeSha = MessageDigest.getInstance("SHA-256").digest(qnnRuntime.singleFile.readBytes())
        .joinToString("") { "%02x".format(it) }
      check(runtimeSha == "a6b9b9f07f7cb968a9df70d83b032463975a90317a366b1c559bcb7f4532b4e3") { "Qualcomm QNN runtime checksum mismatch" }
      copy {
        from(zipTree(qnnRuntime.singleFile))
        include("jni/arm64-v8a/libQnnHtp.so", "jni/arm64-v8a/libQnnSystem.so",
          "jni/arm64-v8a/libQnnHtpV79Stub.so", "jni/arm64-v8a/libQnnHtpV79Skel.so")
        into(output.resolve("jniLibs"))
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
      }
      copy {
        from(zipTree(qnnRuntime.singleFile))
        include("LICENSE.pdf", "NOTICE.txt")
        into(output.resolve("assets/licenses/qualcomm-qnn-2.44.0"))
      }
      // Google's Apache-licensed dispatch bridge, pinned independently of QNN.
      // Never fetch a moving main-branch binary or silently update vendor ABI.
      val bridge = output.resolve("jniLibs/arm64-v8a/libLiteRtDispatch_Qualcomm.so")
      URI("https://raw.githubusercontent.com/google-ai-edge/litert-samples/9ec0c9c0377cf59f84d5000bebd049c98f291e72/samples/litert/qualcomm/gemma3/npu/app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_Qualcomm.so")
        .toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 60_000 }.getInputStream().use { source ->
          bridge.outputStream().use { source.copyTo(it) }
        }
      val sha = MessageDigest.getInstance("SHA-256").digest(bridge.readBytes())
        .joinToString("") { "%02x".format(it) }
      check(sha == "9c039e24d7b80c5a213455cb29f9c2766125dca5f135fa9111c3ef224792e95f") { "Qualcomm dispatch checksum mismatch" }
      copy {
        from(rootProject.file("third_party/litert-samples-LICENSE"))
        into(output.resolve("assets/licenses/litert-qualcomm-dispatch"))
      }
    } else output.mkdirs()
  }
}

android {
  ndkVersion = "28.0.12433566"
  externalNativeBuild { cmake {
    path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1"
    System.getenv("BI_NATIVE_BUILD_DIR")?.let { buildStagingDirectory = file(it) }
  } }
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.betterintelligence.mobile"
    minSdk = 24
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
    targetSdk = 36
    versionCode = 23
    versionName = "2.4.0-beta3"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    manifestPlaceholders["localAiExported"] = "true"
    buildConfigField("boolean", "QUALCOMM_NPU_ENABLED", qualcommNpu.get().toString())
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debug")
      // Side-by-side device validation preserves the user's Play installation.
      if (providers.gradleProperty("biValidationPackage").orNull == "true") {
        applicationIdSuffix = ".validation"
        resValue("string", "app_name", "BI Validation")
        // Opt-in isolated cross-process tests use a separate permission/package;
        // the ordinary validation app remains private.
        manifestPlaceholders["localAiExported"] = if (providers.gradleProperty("biIpcValidation").orNull == "true") "true" else "false"
      }
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
    aidl = true
    resValues = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  // One frozen, test-only beta5 retrieval snapshot supports both JVM corpus
  // comparisons and opt-in real-device before/after probes; never ships in app.
  sourceSets.getByName("androidTest").kotlin.directories += "src/test/java/com/example/researchbaseline"
  sourceSets.getByName("main").jniLibs.directories += qualcommOutput.get().dir("jniLibs").asFile.absolutePath
  sourceSets.getByName("main").assets.directories += qualcommOutput.get().dir("assets").asFile.absolutePath
  // Backend.NPU resolves vendor libraries via applicationInfo.nativeLibraryDir.
  if (qualcommNpu.get()) packaging.jniLibs.useLegacyPackaging = true
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  androidResources {
    noCompress += listOf("gguf", "bin")
  }
}
tasks.named("preBuild") { dependsOn(prepareQualcommNpu) }

tasks.withType<Test>().configureEach {
  maxHeapSize = System.getenv("BI_TEST_HEAP") ?: "3g"
  System.getenv("BI_TEST_TMP_DIR")?.let { systemProperty("java.io.tmpdir", it) }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  if (qualcommNpu.get()) qnnRuntime("com.qualcomm.qti:qnn-runtime:2.44.0@aar")
  implementation("org.jsoup:jsoup:1.23.2")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // Real LiteRT-LM runtime used only for explicitly downloaded .litertlm models.
  implementation(libs.litertlm.android)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
