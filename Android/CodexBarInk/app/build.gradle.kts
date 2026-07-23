plugins {
    id("com.android.application")
}

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val fixtureURL = providers.gradleProperty("codexbarInkFixtureUrl").orElse("").get()
val fixtureToken = providers.gradleProperty("codexbarInkFixtureToken")
    .orElse("codexbar-ink-fixture-token")
    .get()

android {
    namespace = "com.ysimo.codexbar.ink"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ysimo.codexbar.ink"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    flavorDimensions += listOf("transport", "display")
    productFlavors {
        create("fixture") {
            dimension = "transport"
            applicationIdSuffix = ".fixture"
            versionNameSuffix = "-fixture"
            buildConfigField("String", "TRANSPORT_KIND", "\"fixture\"")
            buildConfigField("String", "FIXTURE_URL", fixtureURL.asBuildConfigString())
            buildConfigField("String", "FIXTURE_TOKEN", fixtureToken.asBuildConfigString())
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        create("offline") {
            dimension = "transport"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            buildConfigField("String", "TRANSPORT_KIND", "\"offline\"")
            buildConfigField("String", "FIXTURE_URL", "\"\"")
            buildConfigField("String", "FIXTURE_TOKEN", "\"\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
        create("tailnet") {
            dimension = "transport"
            versionNameSuffix = "-tailnet"
            buildConfigField("String", "TRANSPORT_KIND", "\"tailnet\"")
            buildConfigField("String", "FIXTURE_URL", "\"\"")
            buildConfigField("String", "FIXTURE_TOKEN", "\"\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
        create("generic") {
            dimension = "display"
            buildConfigField("String", "DISPLAY_KIND", "\"generic\"")
        }
        create("boox") {
            dimension = "display"
            buildConfigField("String", "DISPLAY_KIND", "\"boox\"")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("../../../docs/fixtures")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/NOTICE*", "META-INF/LICENSE*")
    }
}

dependencies {
    implementation(project(":reader-core"))
    implementation("androidx.activity:activity:1.13.0")
    implementation("com.google.code.gson:gson:2.13.2")

    add("booxImplementation", "com.onyx.android.sdk:onyxsdk-device:1.3.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
