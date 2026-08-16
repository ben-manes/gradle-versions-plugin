import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

buildscript {
  repositories {
    // Use 'gradle install' to install latest
    mavenLocal()
    gradlePluginPortal()
  }

  dependencies {
    classpath("io.github.ben-manes:gradle-versions-plugin:+")
  }
}

apply(plugin = "io.github.ben-manes.versions")

repositories {
  mavenCentral()
}

configurations {
  register("bom")
  register("bounded")
  register("upToDate")
  register("exceedLatest")
  register("platform")
  register("upgradesFound")
  register("upgradesFound2")
  register("unresolvable")
  register("unresolvable2")
}

fun String.isNonStable(): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { uppercase().contains(it) }
  val regex = "^[0-9,.v-]+(-r|-jre|-android)?$".toRegex()
  val isStable = stableKeyword || regex.matches(this)
  return isStable.not()
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {

  // Example 1: reject all non stable versions
  rejectVersionIf {
    candidate.version.isNonStable()
  }

  // Example 2: disallow release candidates as upgradable versions from stable versions
  rejectVersionIf {
    candidate.version.isNonStable() && !currentVersion.isNonStable()
  }

  // Example 3: using the full syntax
  resolutionStrategy {
    componentSelection {
      all {
        if (candidate.version.isNonStable() && !currentVersion.isNonStable()) {
          reject("Release candidate")
        }
      }
    }
  }

  // Example 4: disallow candidates less mature than the current version
  val qualifiers = listOf("preview", "alpha", "beta", "m", "cr", "rc") // order is important
  fun maturityLevel(version: String): Int {
    val index = qualifiers.indexOfFirst {
      version.matches(".*[.\\-]$it[.\\-\\d]*".toRegex(RegexOption.IGNORE_CASE))
    }
    return if (index < 0) qualifiers.size else index
  }
  rejectVersionIf {
    maturityLevel(candidate.version) < maturityLevel(currentVersion)
  }

  // optional parameters
  checkForGradleUpdate = true
  rejectOutOfBoundVersions = true
  // Turned off so that the examples above are the whole pre-release policy.
  rejectPreReleaseVersions = false
  outputFormatter = "json"
  outputDir = "build/dependencyUpdates"
  reportfileName = "report"
}

dependencies {
  "bom"("org.springframework.boot:spring-boot-dependencies:1.5.8.RELEASE")
  "bom"("com.google.code.gson:gson")
  "bom"("dom4j:dom4j")
  "bounded"("com.google.code.gson:gson") {
    version {
      strictly("[2.8, 2.11[")
    }
  }
  "upToDate"("backport-util-concurrent:backport-util-concurrent:3.1")
  "upToDate"("backport-util-concurrent:backport-util-concurrent-java12:3.1")
  "exceedLatest"("com.google.guava:guava:99.0-SNAPSHOT")
  "exceedLatest"("com.google.guava:guava-tests:99.0-SNAPSHOT")
  "upgradesFound"("com.google.guava:guava:15.0")
  "upgradesFound"("com.google.inject:guice:2.0")
  "upgradesFound"("com.google.inject.extensions:guice-multibindings:2.0")
  "upgradesFound2"("com.google.guava:guava:16.0-rc1")
  "unresolvable"("com.github.ben-manes:unresolvable:1.0")
  "unresolvable"("com.github.ben-manes:unresolvable2:1.0")
  "unresolvable2"("com.github.ben-manes:unresolvable:1.0")
  "unresolvable2"("com.github.ben-manes:unresolvable2:1.0")
  "platform"("com.linecorp.armeria:armeria")
  "platform"("io.zipkin.brave:brave")
  // Common usage would be to separate this into a project that uses the `java-platform` plugin to
  // share constraints among several projects.
  constraints {
    "platform"("com.linecorp.armeria:armeria:0.90.0")
    "platform"("io.zipkin.brave:brave:5.7.0")
  }
}
