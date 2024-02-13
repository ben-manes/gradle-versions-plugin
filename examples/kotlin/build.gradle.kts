import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

buildscript {
  repositories {
    // Use 'gradle install' to install latest
    mavenLocal()
    gradlePluginPortal()
  }

  dependencies {
    classpath("com.github.ben-manes:gradle-versions-plugin:+")
  }
}

apply(plugin = "com.github.ben-manes.versions")

repositories {
  mavenCentral()
}

configurations {
  register("bom")
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
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  val isStable = stableKeyword || regex.matches(this)
  return isStable.not()
}

tasks.withType<DependencyUpdatesTask> {

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

  // optional parameters
  checkForGradleUpdate = true
  outputFormatter = "json"
  outputDir = "build/dependencyUpdates"
  reportfileName = "report"
}

dependencies {
  "bom"("org.springframework.boot:spring-boot-dependencies:1.5.8.RELEASE")
  "bom"("com.google.code.gson:gson")
  "bom"("dom4j:dom4j")
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
