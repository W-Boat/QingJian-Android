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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "QingJian"

include(
  ":core-git",
  ":core-storage",
  ":core-eta",
  ":core-sandbox-js",
  ":editor-muya",
  ":feature-bookshelf",
  ":feature-editor",
  ":feature-lore",
  ":feature-skills",
  ":feature-rp",
  ":app",
)
