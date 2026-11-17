plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.focuslock"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.focuslock"
        minSdk = 23
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
        buildConfigField("String", "GEMINI_API_KEY", "\"${System.getenv("GEMINI_API_KEY") ?: ""}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${System.getenv("GEMINI_MODEL") ?: "gemini-3.8-flash"}\"")
    }
    // Stable signing key (from GitHub secrets) so every update installs OVER the old app
    val ksFile = System.getenv("KEYSTORE_FILE")
    if (!ksFile.isNullOrBlank() && file(ksFile).exists()) {
        signingConfigs.getByName("debug").apply {
            storeFile = file(ksFile)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
