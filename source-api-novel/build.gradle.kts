import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("mihon.library")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.github.ben-manes.versions")
}

kotlin {
    androidTarget()
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlinx.serialization.json)
                api(libs.injekt)
                api(libs.rxjava)
                api(libs.jsoup)
                api(projects.i18n)
                api(kotlinx.reflect)
                implementation(project.dependencies.platform(compose.bom))
                implementation(compose.runtime)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(projects.core.common)
                api(libs.preferencektx)
                implementation(kotlinx.coroutines.android)
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

android {
    namespace = "eu.kanade.tachiyomi.sourcenovel"

    defaultConfig {
        consumerProguardFile("consumer-proguard.pro")
    }
}
