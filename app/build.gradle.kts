plugins {
    id("com.android.application")
}

android {
    namespace = "dev.aragonite.papermill"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aragonite.papermill"
        // BOOX devices that run the Notes app this targets are Android 11 or later.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    // Reads the PDF's page tree: page sizes and the page object numbers Notes keys pages by.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}
