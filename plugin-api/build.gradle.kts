plugins {
    id("mihon.library")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    androidTarget()
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlinx.serialization.json)
                api(kotlinx.coroutines.core)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(kotlinx.coroutines.android)
            }
        }
    }
}

android {
    namespace = "chimahon.plugin.api"
}
