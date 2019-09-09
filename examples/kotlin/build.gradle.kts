import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  id("com.github.ben-manes.versions") version "0.24.0"
}


repositories {
  jcenter()
}

configurations {
  register("bom")
  register("upToDate")
  register("exceedLatest")
  register("upgradesFound")
  register("upgradesFound2")
  register("unresolvable")
  register("unresolvable2")
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
  val regex = "^[0-9,.v-]+$".toRegex()
  val isStable = stableKeyword || regex.matches(version)
  return isStable.not()
}



tasks.withType<DependencyUpdatesTask> {

  rejectVersionIf { selection ->
    isNonStable(selection.candidate.version) && isNonStable(selection.currentVersion).not()
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
}
