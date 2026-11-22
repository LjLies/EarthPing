plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.luccoj.alertroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.luccoj.alertroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        // Only the app's own languages, so library texts match the rest of the app
        resourceConfigurations += listOf("en", "it")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // F-Droid: no Google-encrypted dependency metadata blob in the APK signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all { test ->
                // -Pscreenshots=DIR renders the screens to PNGs (ScreenshotTest)
                providers.gradleProperty("screenshots").orNull?.let { test.systemProperty("screenshots", it) }
                providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                }
            }
        }
    }
}

dependencies {
    // All Apache-2.0. No Google Play Services anywhere.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    // OpenStreetMap map view
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
