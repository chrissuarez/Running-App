import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY", "")
    .replace("\"", "\\\"")
val mapboxAccessToken = localProperties.getProperty("MAPBOX_ACCESS_TOKEN", "")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.runningapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.runningapp"
        // 30 rather than 26 so a backup has exactly one way to snapshot the database (#191).
        // `VACUUM INTO` — the only way to take a copy that cannot silently lag the history it
        // claims to be — arrived in SQLite 3.27, which is API 30. An API-gated fallback would mean
        // a second snapshot path in the one routine that decides whether history survives, only
        // ever exercised on hardware nobody testing this app owns. One path that is unconditionally
        // correct is worth more here than reach into API 26–29.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxAccessToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.5")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Monthly full archive (#85) — WorkManager reschedules itself across reboots, which is what
    // makes an unattended monthly backup survive the phone being turned off.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // FIT export (#218). The official Garmin SDK, published to Maven Central by Garmin themselves
    // (`com.garmin:fit`, "The Official Garmin FIT SDK", scm github.com/garmin/fit-java-sdk) — the
    // CRCs, the definition-message layout and the field scaling are not worth reimplementing.
    //
    // Licence: the FIT Protocol License Agreement, which grants a royalty-free licence "to use the
    // FIT protocol in any software created by Licensee". Two of its terms bind this code rather than
    // this build file: §2h forbids deviating from the FIT protocol, and §3 requires interoperability
    // be maintained — which is why the export is written through the SDK's own encoder and read back
    // by its own decoder in test rather than hand-rolled. §2g forbids safety-critical use; a
    // heart-rate diary is not one.
    //
    // Plain Java 8 with no java.time or java.nio.file anywhere in it, so it needs no desugaring on
    // minSdk 30.
    implementation("com.garmin:fit:21.176.0")

    // Charts (#63). Pinned at 1.13.1 and not to be moved in either direction.
    //
    // Up: 1.14 pulls Compose BOM 2024.02, i.e. a Compose *runtime* of 1.6.1, and the Compose
    // *compiler* here is 1.5.1 — the newest that Kotlin 1.9.0 accepts (#23 deferred the Kotlin
    // upgrade). A 1.6 runtime reading groups a 1.5 compiler wrote corrupts the slot table: on the
    // phone the Progress screen died in Scaffold's subcomposition with
    // `ArrayIndexOutOfBoundsException: length=0; index=-5`, which is what that mismatch looks like.
    // 1.15 and later go further still and require Kotlin 2.x outright.
    //
    // Down: nothing needs an older one. 1.13.1 is built against Compose BOM 2023.10.01, which the
    // BOM above matches, so the whole app stays on the Compose 1.5 line the compiler was made for.
    implementation("com.patrykandpatrick.vico:compose-m3:1.13.1")

    // Mapbox Maps SDK v11 + Compose extension (#40)
    implementation("com.mapbox.maps:android:11.26.0")
    implementation("com.mapbox.extension:maps-compose:11.26.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    // A real SQLite engine on the laptop, so the one statement a backup is made of (#191) can be
    // checked against a real write-ahead log with a real reader holding it open. 3.41 is well past
    // the 3.27 that introduced VACUUM INTO, and past the 3.32 that ships on the app's minSdk-30
    // devices, so what passes here is what the phone runs.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
