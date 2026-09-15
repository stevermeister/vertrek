import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Personal deploy config (Worker URL + shared key), never committed.
// See README Setup step 3 for how to populate local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        FileInputStream(file).use { load(it) }
    }
}

// Local release-signing keystore, never committed. See README Setup step 3.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        FileInputStream(file).use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.github.stevermeister.vertrek"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.stevermeister.vertrek"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "NS_WORKER_BASE_URL",
            "\"${localProperties.getProperty("NS_WORKER_BASE_URL", "")}\"",
        )
        buildConfigField(
            "String",
            "VERTREK_API_KEY",
            "\"${localProperties.getProperty("VERTREK_API_KEY", "")}\"",
        )
        // Ktor HttpTimeout plugin values (WorkerClient.kt) — tunable per
        // network without editing code. Defaults match what actually works
        // over a phone Bluetooth/hotspot companion link, not just Wi-Fi.
        buildConfigField(
            "long",
            "CONNECT_TIMEOUT_MILLIS",
            "${localProperties.getProperty("CONNECT_TIMEOUT_MILLIS", "10000")}L",
        )
        buildConfigField(
            "long",
            "SOCKET_TIMEOUT_MILLIS",
            "${localProperties.getProperty("SOCKET_TIMEOUT_MILLIS", "20000")}L",
        )
        buildConfigField(
            "long",
            "REQUEST_TIMEOUT_MILLIS",
            "${localProperties.getProperty("REQUEST_TIMEOUT_MILLIS", "20000")}L",
        )
        // No STATION_A/STATION_B here: station display names come from the
        // Worker response (fromStationName/toStationName), not from build
        // config — see TripDto.kt / the header in TileLayout.kt/MainActivity.kt.
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Without keystore.properties, assembleRelease still produces an
            // unsigned APK rather than failing — only sideloading needs a
            // signature, and ./gradlew test/assembleDebug shouldn't require one.
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // No kotlinOptions block: with AGP's built-in Kotlin support, jvmTarget
    // defaults to compileOptions.targetCompatibility above.
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // Compose for Wear OS — MainActivity only (the tile is built with
    // androidx.wear.protolayout, added in a later step).
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Data layer
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Ktor — CIO engine has no OkHttp/Retrofit/reflection dependency, the
    // lightest option for the wear target.
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")

    // Tile layout — androidx.wear.protolayout material3 (no Compose here).
    implementation("com.google.guava:guava:33.7.1-android") // ListenableFuture for TileService
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")

    // Tile preview in Android Studio, without a watch/emulator — dev-only.
    debugImplementation("androidx.wear.tiles:tiles-tooling:1.6.2")
    debugImplementation("androidx.wear.tiles:tiles-tooling-preview:1.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock:3.5.2")

    // Structural assertions on the built LayoutElement tree, plus
    // Robolectric because androidx.wear.protolayout.material3's
    // materialScope() requires a real (if minimal) Android Context.
    testImplementation("androidx.wear.protolayout:protolayout-testing:1.4.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}
