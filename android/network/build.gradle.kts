plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.pocketsubstrate.network"
    compileSdk = 36
    buildToolsVersion = "35.0.1"
    defaultConfig { applicationId = "dev.pocketsubstrate.network"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.1.0-experimental" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":ipc")); implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") }
