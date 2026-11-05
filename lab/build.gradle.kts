import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Key ký tạm thời cho FLX10 Lab. File keystore.properties và *.jks KHÔNG được commit (repo public).
// Không có keystore.properties → bản release ký bằng debug key của máy build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "com.xdj.flx10test.lab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xdj.flx10test.lab"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.15.1-lab4"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("lab") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("lab") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        abortOnError = true
        // Lab là công cụ đo, chữ hiển thị viết thẳng trong code.
        disable += setOf("SetTextI18n", "MissingApplicationIcon", "OldTargetApi", "DataExtractionRules")
    }
}

dependencies {
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

tasks.withType<Test>().configureEach {
    listOf("preview.out", "preview.wave", "preview.seconds").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
}
