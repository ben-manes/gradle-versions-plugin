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

// Write the plugin's classpath to a file to share with the tests
tasks.register("createClasspathManifest") {
  val outputDir = layout.buildDirectory.dir(name).get().asFile

  inputs.files(sourceSets.main.get().runtimeClasspath)
  outputs.dir(outputDir)

  doLast {
    outputDir.mkdirs()
    file("$outputDir/plugin-classpath.txt").writeText(sourceSets.main.get().runtimeClasspath.joinToString("\n"))
  }
}

dependencies {
  compileOnly(gradleApi())

  implementation(localGroovy())
  implementation(platform(libs.kotlin.bom))
  implementation(libs.kotlin.stdlib)
  implementation(libs.okhttp)
  implementation(libs.moshi)

  testRuntimeOnly(files(tasks.named("createClasspathManifest")))

  testImplementation(localGroovy())
  testImplementation(gradleTestKit())
  testImplementation(libs.spock) { exclude(module = "groovy-all") }
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
    }
  }
}
