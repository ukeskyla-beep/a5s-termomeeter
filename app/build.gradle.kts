// Otsene import on vajalik: Gradle KTS-is viitab `java` Java-pluginale,
// mitte paketile java.util.
import java.io.File
import java.util.Properties

plugins {
    // AGP 9-st alates on Kotlini tugi Android-pluginasse sisse ehitatud ja
    // org.jetbrains.kotlin.android ei ole enam lubatud. Compose'i kompilaatori
    // plugin on endiselt vajalik. Vt https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Allkirjastamisandmed elavad teadlikult väljaspool repot: võtme kaotamine
// tähendab, et ühtegi uuendust ei saa enam kunagi välja anda, ja repo kaustas
// oleks ta ühe kogemata `git add -f` või kausta kopeerimise kaugusel lekkimast.
//
// Asukoha annab ~/.gradle/gradle.properties (`a5sKeystoreDir=...`) või
// keskkonnamuutuja A5S_KEYSTORE_DIR. Kummagita ehitub release ilma allkirjata,
// nagu CI-s või teisel masinal.
val keystorePropertiesFile: File? =
    (providers.gradleProperty("a5sKeystoreDir").orNull ?: System.getenv("A5S_KEYSTORE_DIR"))
        ?.let { File(it, "keystore.properties") }
        ?.takeIf { it.exists() }

val keystoreProperties = Properties().apply {
    keystorePropertiesFile?.inputStream()?.use { load(it) }
}

android {
    namespace = "ee.ukesk.a5s"
    compileSdk = 37

    defaultConfig {
        applicationId = "ee.ukesk.a5s"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.8"
    }

    signingConfigs {
        val storeFileName = keystoreProperties.getProperty("storeFile")
        if (storeFileName != null && keystorePropertiesFile != null) {
            create("release") {
                // Suhteline tee käib võtmehoidla kausta, mitte repo, järgi.
                storeFile = File(storeFileName)
                    .takeIf { it.isAbsolute }
                    ?: File(keystorePropertiesFile.parentFile, storeFileName)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // ./gradlew assembleRelease -Prtest paigaldab sama minifitseeritud
            // buildi eraldi paketinimega, et R8 vigu saaks kontrollida ilma
            // olemasoleva paigalduse andmeid kustutamata.
            if (project.hasProperty("rtest")) applicationIdSuffix = ".rtest"

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
