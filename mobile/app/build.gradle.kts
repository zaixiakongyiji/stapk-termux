plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stapkVersionName = System.getenv("STAPK_VERSION_NAME") ?: "0.3.0-dev"
val stapkVersionCode = System.getenv("STAPK_VERSION_CODE")?.toIntOrNull() ?: 30000

android {
    namespace = "com.stapk.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stapk.mobile"
        minSdk = 24
        targetSdk = 28
        versionCode = stapkVersionCode
        versionName = stapkVersionName

    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("TERMUX_RELEASE_STORE_FILE")?.let { file(it) }
            storePassword = System.getenv("TERMUX_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("TERMUX_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("TERMUX_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "com/knuddels/jtokkit/p50k_base.tiktoken"
            excludes += "com/knuddels/jtokkit/r50k_base.tiktoken"
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-apache-fileupload:2.3.1") {
        exclude(group = "commons-fileupload", module = "commons-fileupload")
    }
    implementation("commons-fileupload:commons-fileupload:1.6.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
