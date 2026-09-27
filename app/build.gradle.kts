plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Self-hosted GraphHopper Directions API. Empty (the default) means "not configured": the app then
// routes via the public OSRM server below. Set it for a device on the same LAN as a GraphHopper
// instance: `./gradlew assembleDebug -ProutingBaseUrl=http://<host-lan-ip>:8989`.
val routingBaseUrl = providers.gradleProperty("routingBaseUrl").getOrElse("")

// Public Valhalla router run by FOSSGIS (open, no key, fair use). The only open router that reports
// toll roads and can avoid them, so it is first in the public chain.
val valhallaBaseUrl = providers.gradleProperty("valhallaBaseUrl").getOrElse("https://valhalla1.openstreetmap.de")

// Public OSRM demo router (open, no key, no SLA); fallback when Valhalla is unreachable.
val osrmBaseUrl = providers.gradleProperty("osrmBaseUrl").getOrElse("https://router.project-osrm.org")

// Photon (komoot) OSM geocoder: built for search-as-you-type, no key. Override with a self-hosted
// instance for heavier use.
val geocodingBaseUrl = providers.gradleProperty("geocodingBaseUrl").getOrElse("https://photon.komoot.io")

// Overpass API servers for "nearby fuel / parking / …" (open, no key, fair use), comma-separated.
// They are queried in parallel and the first answer wins: public instances are often overloaded.
val overpassBaseUrls = providers.gradleProperty("overpassBaseUrls")
    .getOrElse("https://overpass-api.de,https://maps.mail.ru/osm/tools/overpass,https://overpass.kumi.systems")

// OpenFreeMap OSM vector-tile styles (free, no key). Any MapLibre style URL works here.
val mapDayStyleUrl = providers.gradleProperty("mapDayStyleUrl").getOrElse("https://tiles.openfreemap.org/styles/liberty")
val mapNightStyleUrl = providers.gradleProperty("mapNightStyleUrl").getOrElse("https://tiles.openfreemap.org/styles/dark")

android {
    namespace = "com.csjotlab.cardashboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.csjotlab.cardashboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ROUTING_BASE_URL", "\"$routingBaseUrl\"")
        buildConfigField("String", "VALHALLA_BASE_URL", "\"$valhallaBaseUrl\"")
        buildConfigField("String", "OSRM_BASE_URL", "\"$osrmBaseUrl\"")
        buildConfigField("String", "GEOCODING_BASE_URL", "\"$geocodingBaseUrl\"")
        buildConfigField("String", "OVERPASS_BASE_URLS", "\"$overpassBaseUrls\"")
        buildConfigField("String", "MAP_DAY_STYLE_URL", "\"$mapDayStyleUrl\"")
        buildConfigField("String", "MAP_NIGHT_STYLE_URL", "\"$mapNightStyleUrl\"")
    }

    buildFeatures {
        compose = true
        // AGP 8 stopped generating BuildConfig by default. The mock-source gate reads
        // BuildConfig.DEBUG, and that half of the gate has to be a build-type fact rather than a
        // runtime flag someone can flip.
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // Navigation/map subsystem. MapLibre stays behind the NavigationMap seam in nav/map so the
    // renderer remains replaceable. Pinned to the 11.x line for compileSdk 34 compatibility.
    // kotlinx-serialization-json is used for routing-response parsing without the serialization
    // compiler plugin.
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:1.9.24")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
