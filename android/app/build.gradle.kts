plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.pocketsubstrate"
    compileSdk = 36
    buildToolsVersion = "35.0.1"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.pocketsubstrate"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-experimental"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].jniLibs.srcDir("../vendor/node/bin")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(project(":ipc"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { dependsOn(":network:installDebug") }
val verifyPreparedRuntime by tasks.registering {
    doLast {
        val required = listOf("../vendor/node/include/node/node.h", "../vendor/node/bin/arm64-v8a/libnode.so", "../vendor/node/bin/x86_64/libnode.so", "src/main/assets/web-sdk.js", "src/main/assets/node-bootstrap.cjs", "src/main/assets/node-LICENSE.txt", "src/main/assets/examples/touch-game.psub", "src/main/assets/examples/field-marker.psub")
        check(required.all { file(it).isFile }) { "Missing generated runtime inputs. Run npm ci and npm run prepare:android from the repository root." }
    }
}
tasks.named("preBuild") { dependsOn(verifyPreparedRuntime) }
