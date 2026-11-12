plugins {
    id("com.android.application")
}

android {
    namespace = "com.justepub.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.justepub.reader"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
