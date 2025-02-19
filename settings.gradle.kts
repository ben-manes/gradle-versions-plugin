pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

// Define CI environment variable properly
val isCI: Boolean = System.getenv("CI")?.toBoolean() ?: false

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

// Apply Gradle Plugin
plugins {
  id("com.gradle.develocity") version "3.18.2"
}

// Configure Develocity Plugin Properly
develocity {
  buildScan {
    termsOfUseUrl.set("https://gradle.com/terms-of-service")
    termsOfUseAgree.set("yes")
    publishing.onlyIf { isCI } // Use defined `isCI`
  }
}

// Set project name
rootProject.name = "gradle-versions-plugin"

// Include subprojects
include(":gradle-versions-plugin")
