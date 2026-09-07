plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.example.lanalarm"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.example.lanalarm"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "3.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric drives real Activities, layouts and AlarmManager in
            // the JVM. Without this it cannot see res/, and every test that
            // inflates a layout fails.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // Android stubs org.json in unit tests; this makes the JSON parsing path real.
    testImplementation("org.json:json:20231013")
    // The schedule maths was already covered; the bugs were all in the layer
    // above it — the editor, the list refresh, and what actually reaches
    // AlarmManager. Robolectric is what lets those be tested at all.
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    // The ontoplano client is now the only network path in the app; a real
    // local server is the only honest way to test what it does with 401, 403,
    // 429 and a partial-reject response body.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(17)
}
