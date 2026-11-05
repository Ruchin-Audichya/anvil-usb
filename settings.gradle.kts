pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Anvil"

include(":app")
include(":core:common")
include(":core:blockdev")
include(":core:image")
include(":core:writer")
include(":core:download")
include(":core:catalog")
include(":core:udf")
include(":core:wim")
include(":core:fat")
include(":core:unattend")
include(":tools:devcli")
