plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xdj.flx10test.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
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
        unitTests.isReturnDefaultValues = false
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

// Tên file tiếng Việt: JVM test phải dùng UTF-8 cho tên file (container Linux mặc định là POSIX/ASCII).
tasks.withType<Test>().configureEach {
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
}
