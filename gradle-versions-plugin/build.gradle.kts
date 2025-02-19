plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka) apply false // If using Version Catalogs
  // id("org.jetbrains.dokka") version "1.9.20" apply false // Latest stable
  alias(libs.plugins.ktlint)
  alias(libs.plugins.plugin.publish)
  alias(libs.plugins.versions)
  id("maven-publish") // For publishing the plugin to mavenLocal()
  `java-gradle-plugin`
  `java-library`
  groovy
}

group = properties["GROUP"]?.toString() ?: "default.group"
version = properties["VERSION_NAME"]?.toString() ?: "0.1.0"

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
  implementation("com.google.code.gson:gson:2.10.1")

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
  plugins {
    create("versionsPlugin") {
      id = properties["PLUGIN_NAME"]?.toString() ?: "com.example.plugin"
      implementationClass = properties["PLUGIN_NAME_CLASS"]?.toString() ?: "com.example.Plugin"
      displayName = properties["POM_NAME"]?.toString() ?: "Gradle Versions Plugin"
      description = properties["POM_DESCRIPTION"]?.toString() ?: "Automatically updates dependencies"
    }
  }
}

// ✅ Move metadata to `pluginBundle {}` instead
pluginBundle {
  website = properties["POM_URL"]?.toString() ?: "https://default-url.com"
  vcsUrl = properties["POM_SCM_URL"]?.toString() ?: "https://github.com/example/repo"
  tags = listOf("dependencies", "versions", "updates")
}
