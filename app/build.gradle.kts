plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing (guide: LjLies/Android-Development). The keystore and its password never go
// in the repository: Gradle takes them from ~/.gradle/gradle.properties (releaseStoreFile,
// releaseStorePassword, optional releaseKeyAlias) or, on CI, from environment variables
// (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS). Without them, release builds
// come out unsigned.
fun signingSetting(property: String, variable: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(variable).orNull)
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingSetting("releaseStoreFile", "RELEASE_STORE_FILE")
    ?.let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }

android {
    namespace = "io.github.ljlies.earthping"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ljlies.earthping"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.3.7"
        // Only the app's own languages, so library texts match the rest of the app
        resourceConfigurations += listOf("en", "it")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingSetting("releaseStorePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingSetting("releaseKeyAlias", "RELEASE_KEY_ALIAS") ?: "release"
                // PKCS12 keystores (keytool's default) use one password for the store and the key
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
