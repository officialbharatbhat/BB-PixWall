plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(
                providers.gradleProperty("BBPIX_STORE_FILE").get()
            )
            storePassword =
                providers.gradleProperty("BBPIX_STORE_PASSWORD").get()
            keyAlias =
                providers.gradleProperty("BBPIX_KEY_ALIAS").get()
            keyPassword =
                providers.gradleProperty("BBPIX_KEY_PASSWORD").get()
        }
    }

    namespace = "bb.pix.wall"
    compileSdk = 37

    defaultConfig {
        applicationId = "bb.pix.wall"
        minSdk = 35
        targetSdk = 37
        versionCode = 14
        versionName = "1.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
