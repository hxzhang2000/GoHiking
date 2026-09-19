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

rootProject.name = "GoHiking"

include(":app")
include(":core:common")
include(":core:database")
include(":core:data")
include(":core:datastore")
include(":core:designsystem")
include(":core:elevation")
include(":core:location")
include(":core:map")
include(":core:model")
include(":core:resources")
include(":feature:home")
include(":feature:history")
include(":feature:io")
include(":feature:media")
include(":feature:plan")
include(":feature:recording")
include(":feature:settings")
