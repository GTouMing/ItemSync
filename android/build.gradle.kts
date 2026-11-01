plugins {
    id("com.android.application")
}

android {
    namespace = "github.gtouming.itemsync.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "github.gtouming.itemsync"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-SNAPSHOT"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    implementation(project(":core-sync"))
    implementation(project(":core-store"))

    testImplementation(project(":core-store-testkit"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
