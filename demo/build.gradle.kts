import java.nio.file.Files
import java.nio.file.Paths

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.inksdk.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.inksdk.demo"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release { isMinifyEnabled = false }
    }

    packaging {
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                val realHome = System.getProperty("user.home")
                val roboHome = file("$realHome/.gradle/robolectric-home")
                roboHome.mkdirs()
                val m2Link = file("${roboHome.absolutePath}/.m2")
                if (!m2Link.exists()) {
                    Files.createSymbolicLink(m2Link.toPath(), Paths.get("$realHome/.m2"))
                }
                it.systemProperty("user.home", roboHome.absolutePath)
            }
        }
    }
}

dependencies {
    implementation(project(":inkcontroller"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}
