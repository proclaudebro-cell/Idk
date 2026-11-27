plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.floattok"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.floattok"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI has no release key; it signs release with the debug key so the build is installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        xmlReport = false
        textReport = true
        htmlReport = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Framework-only app: no AndroidX, so the same sources also build with the offline toolchain in tools/.
    testImplementation("junit:junit:4.13.2")
}
