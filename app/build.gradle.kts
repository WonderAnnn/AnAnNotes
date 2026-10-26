import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.anan.anannotes"
    compileSdk = 36
    sourceSets {
      getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    defaultConfig {
        applicationId = "com.anan.anannotes"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing stays opt-in: the keystore and passwords live only in the
    // gitignored keystore.properties at the repo root (see keystore.properties.example);
    // without that file the release build remains unsigned, exactly as before.
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val hasReleaseSigning = keystorePropertiesFile.isFile
    val keystoreProperties = Properties()
    if (hasReleaseSigning) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("releaseFromLocalProperties") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseFromLocalProperties")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    // Launcher JVM is the Android Studio JBR (Java 25.0.3 as of 2026-10-25); the
    // foojay resolver in settings.gradle.kts provisions the pinned Java 21 toolchain,
    // and bytecode targets above stay at Java 17.
    jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.runtime)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.room.testing)
  ksp(libs.androidx.room.compiler)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Argon2id is provided by Bouncy Castle's lightweight API; AES-GCM stays on Android JCA.
  implementation(libs.bouncyCastle.provider)
  // Offline OCR engine; language data is bundled locally and never downloaded at runtime.
  implementation(libs.tesseract4android)
  implementation(libs.androidx.work.runtime.ktx)
}

val verifyReleaseNoInternetPermission = tasks.register<Exec>("verifyReleaseNoInternetPermission") {
  group = "verification"
  description = "Fails release packaging when the merged app manifest requests INTERNET permission."
  dependsOn("processReleaseMainManifest")
  val mergedManifest = layout.buildDirectory.file(
    "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
  )
  val checker = layout.projectDirectory.file("scripts/VerifyReleaseNoInternetPermission.java")
  inputs.file(mergedManifest)
  inputs.file(checker)
  commandLine("java", "--source", "17", checker.asFile.absolutePath, mergedManifest.get().asFile.absolutePath)
}

tasks.configureEach {
  if (name == "assembleRelease" || name == "bundleRelease") {
    dependsOn(verifyReleaseNoInternetPermission)
  }
}
