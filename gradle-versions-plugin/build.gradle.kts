import org.gradle.plugin.compatibility.compatibility

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.plugin.publish)
  alias(libs.plugins.versions)
  id("maven-publish") // For publishing the plugin to mavenLocal()
  `java-gradle-plugin`
  `java-library`
  groovy
}

group = properties["GROUP"].toString()
version = properties["VERSION_NAME"].toString()

// The plugin runs in every Gradle from the oldest supported release on, so it compiles against that
// release's API in place of the API of the Gradle running this build, which `java-gradle-plugin`
// adds. A call into a newer API then fails to compile instead of failing at runtime on an older Gradle.
configurations.compileOnlyApi {
  dependencies.removeIf { it is FileCollectionDependency }
}

dependencies {
  compileOnly(libs.gradle.api.minimum)
  compileOnly(libs.groovy.minimum)
  implementation(platform(libs.kotlin.bom))
  implementation(libs.kotlin.stdlib)
  implementation(libs.okhttp)
  implementation(libs.moshi)

  testImplementation(localGroovy())
  testImplementation(gradleTestKit())
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.spock) { exclude(module = "groovy-all") }
  testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
  website.set(properties["POM_URL"].toString())
  vcsUrl.set(properties["POM_SCM_URL"].toString())
  plugins {
    create("versionsPlugin") {
      id = properties["PLUGIN_NAME"].toString()
      implementationClass = properties["PLUGIN_NAME_CLASS"].toString()
      displayName = properties["POM_NAME"].toString()
      description = properties["POM_DESCRIPTION"].toString()
      tags.set(listOf("dependencies", "versions", "updates"))
      compatibility {
        features {
          configurationCache = true
        }
      }
    }
    create("legacyVersionsPlugin") {
      id = properties["PLUGIN_LEGACY_NAME"].toString()
      implementationClass = properties["PLUGIN_LEGACY_NAME_CLASS"].toString()
      displayName = properties["POM_LEGACY_NAME"].toString()
      description = properties["POM_LEGACY_DESCRIPTION"].toString()
      tags.set(listOf("dependencies", "versions", "updates"))
      compatibility {
        features {
          configurationCache = true
        }
      }
    }
    create("versionsContributorPlugin") {
      id = properties["PLUGIN_CONTRIBUTOR_NAME"].toString()
      implementationClass = properties["PLUGIN_CONTRIBUTOR_NAME_CLASS"].toString()
      displayName = properties["POM_CONTRIBUTOR_NAME"].toString()
      description = properties["POM_CONTRIBUTOR_DESCRIPTION"].toString()
      tags.set(listOf("dependencies", "versions", "updates"))
      compatibility {
        features {
          configurationCache = true
        }
      }
    }
    create("versionsSettingsPlugin") {
      id = properties["PLUGIN_SETTINGS_NAME"].toString()
      implementationClass = properties["PLUGIN_SETTINGS_NAME_CLASS"].toString()
      displayName = properties["POM_SETTINGS_NAME"].toString()
      description = properties["POM_SETTINGS_DESCRIPTION"].toString()
      tags.set(listOf("dependencies", "versions", "updates"))
      compatibility {
        features {
          configurationCache = true
        }
      }
    }
  }
}
