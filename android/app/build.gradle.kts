plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "sk.cartracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.cartracker"
        minSdk = 26
        targetSdk = 35
        // Číslo buildu z GitHub Actions → každá nová verzia má vyššie číslo a dá sa nainštalovať ako aktualizácia
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + build
        versionName = "2.0.$build"
    }

    // Pevný podpisový kľúč – rovnaký pre všetky buildy, inak Android aktualizáciu odmietne
    signingConfigs {
        create("shared") {
            storeFile = file("cartracking.jks")
            storePassword = System.getenv("CT_STORE_PASSWORD") ?: "cartracking123"
            keyAlias = "cartracking"
            keyPassword = System.getenv("CT_KEY_PASSWORD") ?: "cartracking123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Poloha + rozpoznávanie aktivity (jazda autom)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Lokálna DB (offline buffer) + nastavenia
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Synchronizácia so serverom (PHP + MySQL)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Odtlačok prsta
    implementation("androidx.biometric:biometric:1.1.0")

    // Mapa trasy (OpenStreetMap – nepotrebuje API kľúč)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Testy výpočtov (km, limity, účel jazdy) – bežia pri každom builde na GitHube
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
