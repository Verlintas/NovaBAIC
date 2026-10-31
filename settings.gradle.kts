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

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android: ADB pairing/shell without installing Shizuku.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BetterAIChat2"

include(":app")

include(":core:model")
include(":core:data")
include(":core:network")
include(":core:engine")
include(":core:runtime")
include(":core:designsystem")

include(":feature:chat")
include(":feature:conversations")
include(":feature:tasks")
include(":feature:settings")
include(":feature:agents")
include(":feature:library")

include(":device:api")
include(":device:impl")

include(":tools")
include(":mcp")
include(":eval")
