import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is supplied outside the repository; without it, release builds are unsigned.
val signingFile = File(System.getProperty("user.home"), ".local/share/aragonite-papermill/signing/release.properties")
val releaseKey = Properties()
if (signingFile.isFile) {
    signingFile.inputStream().use { releaseKey.load(it) }
}

android {
    namespace = "dev.aragonite.papermill"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aragonite.papermill"
        // BOOX devices that run the Notes app this targets are Android 11 or later.
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    if (signingFile.isFile) {
        val persistent = signingConfigs.create("persistent") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
        buildTypes.named("release") {
            signingConfig = persistent
        }
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
