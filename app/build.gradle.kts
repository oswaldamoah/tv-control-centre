plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.oswald.tvcontrol"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.oswald.tvcontrol"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
    }
}
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
