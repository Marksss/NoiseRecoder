plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.noiserecorder"
    compileSdk = 35
    defaultConfig { applicationId = "com.example.noiserecorder"; minSdk = 30; targetSdk = 35; versionCode = 15; versionName = "1.8.1"; testInstrumentationRunner = "com.example.noiserecorder.SmokeInstrumentation" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
