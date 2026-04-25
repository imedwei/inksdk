import java.nio.file.Files
import java.nio.file.Paths

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.inksdk.ink"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        // Onyx pen SDK and mmkv (transitive of onyxsdk-base) both ship the
        // same arm64-v8a libc++_shared.so. Pick one.
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                // Sandboxed-CI Robolectric workaround (matches Mokke setup):
                // redirect user.home so the .robolectric-download-lock and
                // ~/.m2/repository cache resolve under writable paths.
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

configurations.all {
    // Onyx SDK pulls in old pre-AndroidX support libraries that clash with
    // AndroidX. Exclude them — consumers should be on AndroidX in 2026.
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "support-annotations")
    exclude(group = "com.android.support", module = "support-v4")
}

dependencies {
    api("androidx.annotation:annotation:1.9.1")

    // Onyx Pen SDK — required by OnyxInkController; on non-Onyx devices the
    // classes load but the vendor runtime is missing, attach() returns false.
    api("com.onyx.android.sdk:onyxsdk-pen:1.5.2")
    api("com.onyx.android.sdk:onyxsdk-device:1.3.3")
    // HiddenAPIBypass is required for the Onyx SDK on Android 14+ — host
    // apps need to call HiddenApiBypass.addHiddenApiExemptions("L") at
    // Application.onCreate(). Pulled in as `api` so consumers don't need to
    // manually add the dep.
    api("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
