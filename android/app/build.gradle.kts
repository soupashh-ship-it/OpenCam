import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. A keystore.properties next to this file wins; without it the
// debug keystore is used, so `assembleRelease` always produces an installable,
// signed APK rather than an unsigned one that no device will take.
val keystoreProperties = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystoreProperties.exists()

android {
    namespace = "app.opencam"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.opencam"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                val props = Properties()
                keystoreProperties.inputStream().use { props.load(it) }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

// The APK is named after the product: opencam.apk for a release build,
// opencam-debug.apk for a debug one.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val name = if (variant.buildType == "release") {
                "opencam.apk"
            } else {
                "opencam-${variant.buildType}.apk"
            }
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName
                .set(name)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
