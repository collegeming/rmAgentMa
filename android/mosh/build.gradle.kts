/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.rmagentma.build.PrepareMoshArtifacts

plugins {
    alias(libs.plugins.android.dynamic.feature)
}

val moshReleaseTag = rootProject.file("gradle/mosh4android.version")
    .readLines().first { it.isNotBlank() && !it.startsWith("#") }.trim()

val generatedGoogleMosh = layout.buildDirectory.dir("generated/mosh/google")
val prepareGoogleMoshArtifacts = tasks.register<PrepareMoshArtifacts>("prepareGoogleMoshArtifacts") {
    releaseTag.set(moshReleaseTag)
    downloadDirectory.set(layout.buildDirectory.dir("mosh-downloads"))
    jniLibsDirectory.set(generatedGoogleMosh.map { it.dir("jniLibs") })
    assetsDirectory.set(generatedGoogleMosh.map { it.dir("assets") })
}

android {
    namespace = "org.connectbot.mosh"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    flavorDimensions += listOf("license")
    productFlavors {
        create("oss") {
            dimension = "license"
        }
        create("google") {
            dimension = "license"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants(selector().withFlavor("license" to "google")) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareGoogleMoshArtifacts) { it.jniLibsDirectory }
        variant.sources.assets?.addGeneratedSourceDirectory(prepareGoogleMoshArtifacts) { it.assetsDirectory }
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
        variant.packaging.jniLibs.useLegacyPackagingFromBundle.set(true)
    }
}

dependencies {
    implementation(project(":app"))
}
