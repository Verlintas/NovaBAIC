/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(environment: String, property: String): String? =
    System.getenv(environment) ?: keystoreProperties.getProperty(property)

android {
    namespace = "com.verlintas.baic2"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.verlintas.baic2"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "0.2.1"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("BAIC2_KEYSTORE_PATH", "storeFile")
            val store = storePath?.let { path ->
                val file = rootProject.file(path)
                if (file.exists()) file else null
            }
            if (store != null) {
                storeFile = store
                storePassword = signingValue("BAIC2_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("BAIC2_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("BAIC2_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:engine"))
    implementation(project(":core:runtime"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:conversations"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:library"))
    implementation(project(":device:api"))
    implementation(project(":device:impl"))
    implementation(project(":tools"))
    implementation(project(":mcp"))
    implementation(project(":eval"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
