plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose)
}

android {
    namespace = "io.github.bytenomad47.a20"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.bytenomad47.a20"
        minSdk = 31
        targetSdk = 37
        versionCode = 19
        versionName = "0.5.0-sleep-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2",
            )
            listOf(
                    "http.proxyHost",
                    "http.proxyPort",
                    "https.proxyHost",
                    "https.proxyPort",
                    "javax.net.ssl.trustStore",
                )
                .forEach { name ->
                    System.getProperty(name)?.let { value -> it.systemProperty(name, value) }
                }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (providers.environmentVariable("A20_SIGNING_STORE").isPresent)
            create("preview") {
                storeFile = file(providers.environmentVariable("A20_SIGNING_STORE").get())
                storePassword = providers.environmentVariable("A20_SIGNING_PASSWORD").get()
                keyAlias = "a20-companion"
                keyPassword = providers.environmentVariable("A20_SIGNING_PASSWORD").get()
            }
    }
    buildTypes {
        release {
            if (signingConfigs.findByName("preview") != null)
                signingConfig = signingConfigs.getByName("preview")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:device"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.preview)
    implementation(libs.material3)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
