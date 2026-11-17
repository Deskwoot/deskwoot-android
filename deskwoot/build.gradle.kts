plugins {
    // Kotlin is compiled by AGP 9's built-in support (see the root build file).
    id("com.android.library")
    `maven-publish`
    signing
}

// The SDK's version, from gradle.properties: the published artifact's, and Deskwoot.SDK_VERSION (below).
val sdkVersion: String = providers.gradleProperty("deskwootSdkVersion").get()

group = "com.deskwoot"
version = sdkVersion

android {
    namespace = "com.deskwoot.sdk"
    compileSdk = 36
    // Every resource of the SDK starts with dw_ (lint's ResourceName check), so
    // none clashes with an app's own; res/values/public.xml keeps all but the
    // view ids for UI tests private.
    resourcePrefix = "dw_"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        // androidx.activity 1.9 and androidx.core 1.13 (the lowest versions with
        // predictive back progress and the photo picker contract) need apps to
        // compile against API 34; the SDK's own code needs nothing newer.
        aarMetadata {
            minCompileSdk = 34
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric reads the library's resources (strings in 10 languages, drawables).
            isIncludeAndroidResources = true
        }
    }

    publishing {
        // The release AAR with its sources and its API documentation (Maven Central
        // asks for both jars); no debug variant is published.
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    // Apps on older Kotlin toolchains can use the library: the oldest language
    // and API version the Kotlin 2.4 compiler still accepts (1.9 is refused,
    // 2.0 and 2.1 are deprecated), and the standard library of that line as
    // the dependency instead of 2.4's.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        // The only warning this causes is the deprecation of version 2.0 itself; every other warning stays an error.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin")) {
        // The tests drive virtual time with kotlinx-coroutines-test, whose scheduler API is marked experimental.
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

/** Writes the version into the SDK's sources, so Deskwoot.SDK_VERSION (sent to the server) is the published one. */
abstract class SdkVersionSource : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val file = outputDir.get().file("com/deskwoot/sdk/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Written by the build from deskwootSdkVersion in gradle.properties; do not edit.
            |package com.deskwoot.sdk
            |
            |@field:JvmSynthetic
            |internal const val SDK_VERSION_NAME: String = "${versionName.get()}"
            |
            """.trimMargin(),
        )
    }
}

val sdkVersionSource = tasks.register<SdkVersionSource>("sdkVersionSource") {
    versionName.set(sdkVersion)
    outputDir.set(layout.buildDirectory.dir("generated/source/sdkVersion"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(sdkVersionSource, SdkVersionSource::outputDir)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // An api dependency: public signatures carry its annotations (DrawableRes, MainThread).
    api("androidx.annotation:annotation:1.9.1")
    // profileinstaller (through androidx.lifecycle) would add a ContentProvider
    // that starts with the app and an exported receiver; the SDK starts nothing
    // on its own, so every androidx edge that leads to it leaves it out (a
    // module is dropped only when all paths to it exclude it). The exclusions
    // reach apps through the published POM and module metadata.
    // Window insets, accessibility and FileProvider. An api dependency: the
    // public DeskwootFileProvider extends androidx.core's FileProvider.
    api("androidx.core:core:1.13.1") {
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
    // ComponentActivity: the back dispatcher (predictive back) and the result
    // contracts of the photo picker, the camera and the document picker. An api
    // dependency: the public DeskwootActivity extends ComponentActivity.
    api("androidx.activity:activity:1.9.3") {
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
    // The thread and every list: recycling, DiffUtil off the main thread.
    implementation("androidx.recyclerview:recyclerview:1.3.2") {
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Android's own org.json (AOSP libcore, repackaged on Maven Central), not the
    // org.json:json reference implementation: the two differ on NaN, deep nesting,
    // null and toString, and the tests must see what a phone does.
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    testImplementation("org.robolectric:robolectric:4.16")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.deskwoot"
            artifactId = "deskwoot-android"
            version = sdkVersion
            // AGP makes the release component once the project is evaluated.
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Deskwoot Android SDK")
                description.set("Deskwoot's chat for Android apps: the inbox's messenger as native Android views, with the help center, push notifications and the launcher.")
                url.set("https://deskwoot.com")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("deskwoot")
                        name.set("Deskwoot")
                        url.set("https://deskwoot.com")
                        organization.set("Neltacore LLC")
                        organizationUrl.set("https://deskwoot.com")
                    }
                }
                scm {
                    url.set("https://github.com/Deskwoot/deskwoot-android")
                    connection.set("scm:git:https://github.com/Deskwoot/deskwoot-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Deskwoot/deskwoot-android.git")
                }
            }
        }
    }
    repositories {
        // For a Maven Central upload: the release publication in the Maven layout with its md5 and sha1 files (and the
        // .asc signatures when the signing properties are given), ready to zip from build/central-bundle.
        maven {
            name = "bundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }
}

// Signed only when the keys are given as Gradle properties (-PsigningKey=... -PsigningPassword=..., or
// ~/.gradle/gradle.properties): nothing secret lives in the repository, and a build without them signs nothing.
val signingKey: String? = providers.gradleProperty("signingKey").orNull
signing {
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingPassword").orNull)
        sign(publishing.publications)
    }
}
