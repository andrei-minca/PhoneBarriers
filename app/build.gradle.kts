import java.util.Properties

plugins {
    alias(libs.plugins.android.application)

    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")

}

android {
    namespace = "ro.andi.phonebarriers"
    compileSdk = 37

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
        // test barrier shortname
        buildConfigField("String", "TEST_BARRIER_SHORTNAME",
            properties.getProperty("TEST_BARRIER_SHORTNAME") ?: "\"BARRIER\"")
        // test barrier description
        buildConfigField("String", "TEST_BARRIER_DESCRIPTION",
            properties.getProperty("TEST_BARRIER_DESCRIPTION") ?: "\"BARRIER-DESCRIPTION\"")
        // test barrier color
        buildConfigField("String", "TEST_BARRIER_COLOR",
            properties.getProperty("TEST_BARRIER_COLOR") ?: "\"0x00000000\"")
        // to & from phone numbers
        buildConfigField("String", "TEST_BARRIER_PHONE_NUMBER_TO",
            properties.getProperty("TEST_BARRIER_PHONE_NUMBER_TO") ?: "\"\"")
        buildConfigField("String", "TEST_BARRIER_PHONE_NUMBER_FROM",
            properties.getProperty("TEST_BARRIER_PHONE_NUMBER_FROM") ?: "\"\"")
        // latitude & longitude & radius
        buildConfigField("String", "TEST_BARRIER_LATITUDE",
            properties.getProperty("TEST_BARRIER_LATITUDE") ?: "\"0.0\"")
        buildConfigField("String", "TEST_BARRIER_LONGITUDE",
            properties.getProperty("TEST_BARRIER_LONGITUDE") ?: "\"0.0\"")
        buildConfigField("String", "TEST_BARRIER_RADIUS",
            properties.getProperty("TEST_BARRIER_RADIUS") ?: "\"0\"")
        
        // maps api key - kept fix (no quotes in local.properties, wrapped here for BuildConfig)
        val mapsApiKey = properties.getProperty("MAPS_API_KEY") ?: ""
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")

        // default active hours list
        buildConfigField("String", "DEFAULT_ACTIVE_HOURS_LIST",
            properties.getProperty("DEFAULT_ACTIVE_HOURS_LIST") ?: "\"8,9,10,11,12,13,14,15,16,17\"")

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
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

    buildFeatures {
        compose = true
        buildConfig = true
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    implementation(libs.androidx.compose.ui)

    // Material Design 3 (The current standard)
    implementation(libs.androidx.compose.material3)

    // Integration with Activities
    implementation(libs.androidx.activity.compose)

    // Networking (OkHttp for your Twilio calls)
    implementation(libs.okhttp)

    // Android Studio Preview support
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Gson library for JSON serialization/deserialization in AppPreferences
    implementation(libs.gson) // Or the latest stable version

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    implementation(libs.androidx.material.icons.extended)
}
