import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun ks(key: String): String? = System.getenv(key) ?: ksProps.getProperty(key)

android {
    namespace = "io.github.fadhil_sabar.tulis"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.fadhil_sabar.tulis"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "0.1.0"
    }

    signingConfigs {
        if (ks("KEYSTORE_FILE") != null) {
            create("release") {
                storeFile = file(ks("KEYSTORE_FILE")!!)
                storePassword = ks("KEYSTORE_PASSWORD")
                keyAlias = ks("KEY_ALIAS")
                keyPassword = ks("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
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
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.ink:ink-storage:1.0.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
