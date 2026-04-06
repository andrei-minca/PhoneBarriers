import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    // Add this line (version should match your Kotlin version: 2.0.21)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"

    id("com.google.devtools.ksp") version "2.0.21-1.0.27" // Match Kotlin 2.0.21

}

android {
    namespace = "ro.andi.phonebarriers"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.andi.phonebarriers"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // read from local.properties
        val properties = Properties()
        val propertiesFile = project.rootProject.file("local.properties")
        if (propertiesFile.exists()) {
            properties.load(propertiesFile.inputStream())
        }

        // url & secret
        buildConfigField("String", "TWILIO_FUNC_URL",
            properties.getProperty("TWILIO_FUNC_URL_MAKE_ONE_RING_5_SECONDS") ?: "\"\"")
        buildConfigField("String", "TWILIO_FUNC_SECRET",
            properties.getProperty("TWILIO_FUNC_SECRET") ?: "\"\"")
        // to & from phone numbers
        buildConfigField("String", "TEST_PHONE_NUMBER_TO",
            properties.getProperty("TEST_PHONE_NUMBER_TO") ?: "\"\"")
        buildConfigField("String", "TEST_PHONE_NUMBER_FROM",
            properties.getProperty("TEST_PHONE_NUMBER_FROM") ?: "\"\"")
        // test barrier name
        buildConfigField("String", "TEST_BARRIER_NAME",
            properties.getProperty("TEST_BARRIER_NAME") ?: "\"BARRIER\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)


    // Import the Compose BOM (Bill of Materials)
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Compose libraries
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Material Design 3 (The current standard)
    implementation("androidx.compose.material3:material3")

    // Integration with Activities
    implementation("androidx.activity:activity-compose:1.9.0")

    // Networking (OkHttp for your Twilio calls)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Android Studio Preview support
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    val room_version = "2.6.1"

    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")


}