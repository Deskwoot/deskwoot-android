import java.util.Properties

plugins {
    id("com.android.application")
}

// The server, the widget token and the test email the example uses. Each comes from a Gradle
// property (-PdeskwootWidgetToken=..., or ~/.gradle/gradle.properties), else from an environment
// variable (DESKWOOT_WIDGET_TOKEN), else from the project's local.properties (deskwootWidgetToken=...).
// Without any, the app talks to deskwoot.com with a placeholder token, which the server refuses.
val localProperties = Properties()
rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { localProperties.load(it) }

fun exampleSetting(name: String, environment: String, default: String): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(environment).orNull
        ?: localProperties.getProperty(name)
        ?: default

android {
    namespace = "com.deskwoot.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deskwoot.example"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The server's origin. A local server on your computer is http://10.0.2.2:<port> from the emulator.
        manifestPlaceholders["deskwootBaseUrl"] = exampleSetting("deskwootBaseUrl", "DESKWOOT_BASE_URL", "https://deskwoot.com")
        // Your inbox's widget token (Settings, Inboxes, your inbox, Chat in your iOS and Android app).
        val widgetToken = exampleSetting("deskwootWidgetToken", "DESKWOOT_WIDGET_TOKEN", "YOUR_WIDGET_TOKEN")
        buildConfigField("String", "WIDGET_TOKEN", "\"$widgetToken\"")
        // The email the "identify" button sends for its test user; empty sends none.
        val testEmail = exampleSetting("deskwootTestEmail", "DESKWOOT_TEST_EMAIL", "")
        buildConfigField("String", "TEST_EMAIL", "\"$testEmail\"")
        // FOR LOCAL TESTS ONLY: the identity secret of a local test inbox, so the example can sign its
        // test user itself. A real app never ships an identity secret; its backend computes the identifierHash.
        buildConfigField("String", "LOCAL_IDENTITY_SECRET", "\"sdk_local_identity_secret\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":deskwoot"))
}
