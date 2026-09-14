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

// Some specs load Gradle in process, and the Gradle running this build does not start below JDK 17.
// The specs are compiled a second time against the oldest supported release for the older JDKs, so
// that both the in-process specs and the TestKit specs run against that release there.
val minimumGradleTest =
  sourceSets.create("minimumGradleTest") {
    groovy.setSrcDirs(listOf("src/test/groovy"))
    resources.setSrcDirs(listOf("src/test/resources"))
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }

configurations.named(minimumGradleTest.implementationConfigurationName) {
  extendsFrom(configurations.implementation.get())
}

// Groovy 3 fails on the class files of the JDK this build runs on.
tasks.named<GroovyCompile>(minimumGradleTest.getCompileTaskName("groovy")) {
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
}

// The wrapper runs on the newest JDK, so the older ones are reached through toolchains: the test
// worker forks on the named JDK and the TestKit daemons follow it. `test` stays on the build JVM as
// the fast loop, and `check` runs JDK 8 on the oldest supported Gradle as well, the opposite corner.
val testOnAllJdks =
  tasks.register("testOnAllJdks") {
    description = "Runs the test suite on every JDK the plugin supports."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.test)
  }

// `test` already runs on the build JVM, so the task named for that JDK runs `test` instead of a
// second copy of the suite.
val buildJdk = JavaVersion.current().majorVersion
tasks.register("testOn$buildJdk") {
  description = "Runs the test suite on JDK $buildJdk, the JDK the build runs on."
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  dependsOn(tasks.test)
}

// A build resolves the plugin's dependencies from its published metadata, which TestKit's plugin
// classpath leaves out, so a spec that needs them resolves the plugin from this repository instead.
val pluginId = properties["PLUGIN_NAME"].toString()
val pluginModule = "${project.group.toString().replace('.', '/')}/${project.name}"
val pluginVersion = version.toString()
val specsRepositoryDir = layout.buildDirectory.dir("specs-repository")
val specsRepository =
  tasks.register<Sync>("specsRepository") {
    into(specsRepositoryDir)
    into("$pluginModule/$pluginVersion") {
      from(tasks.jar)
      from(tasks.named("generatePomFileForPluginMavenPublication")) {
        rename("pom-default\\.xml", "${project.name}-$pluginVersion.pom")
      }
      from(tasks.named("generateMetadataFileForPluginMavenPublication")) {
        rename("module\\.json", "${project.name}-$pluginVersion.module")
      }
    }
    into("${pluginId.replace('.', '/')}/$pluginId.gradle.plugin/$pluginVersion") {
      from(tasks.named("generatePomFileForVersionsPluginPluginMarkerMavenPublication")) {
        rename("pom-default\\.xml", "$pluginId.gradle.plugin-$pluginVersion.pom")
      }
    }
  }

listOf(8, 11, 17, 21).forEach { jdk ->
  val testOnJdk =
    tasks.register<Test>("testOn$jdk") {
      description = "Runs the test suite on JDK $jdk."
      group = LifecycleBasePlugin.VERIFICATION_GROUP
      javaLauncher.set(
        javaToolchains.launcherFor {
          languageVersion.set(JavaLanguageVersion.of(jdk))
        },
      )
      val specs = if (jdk < 17) minimumGradleTest else sourceSets.test.get()
      testClassesDirs = specs.output.classesDirs
      classpath = specs.runtimeClasspath
      // The specs that name no release of their own drive the oldest supported release here too.
      if (jdk < 17) {
        systemProperty("testGradleVersion", libs.versions.gradle.minimum.get())
      }
      // The specs that resolve the plugin from a repository run Gradle 8.4, which fails to start on the
      // build JVM.
      inputs.files(specsRepository).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("specsRepository")
      systemProperty("specsRepository", specsRepositoryDir.get().asFile.relativeTo(projectDir).path)
      systemProperty("pluginId", pluginId)
      systemProperty("pluginVersion", pluginVersion)
    }
  testOnAllJdks.configure { dependsOn(testOnJdk) }
}

tasks.check {
  dependsOn("testOn8")
}

dependencies {
  compileOnly(libs.gradle.api.minimum)
  compileOnly(libs.groovy.minimum)
  implementation(libs.kotlin.stdlib)
  implementation(libs.okhttp)
  implementation(libs.moshi)

  testImplementation(localGroovy())
  testImplementation(gradleTestKit())
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.spock) { exclude(module = "groovy-all") }
  testRuntimeOnly(libs.junit.platform.launcher)

  "minimumGradleTestImplementation"(libs.gradle.api.minimum)
  "minimumGradleTestImplementation"(libs.gradle.test.kit.minimum)
  "minimumGradleTestImplementation"(libs.groovy.minimum)
  "minimumGradleTestImplementation"(libs.kotlin.reflect)
  "minimumGradleTestImplementation"(libs.spock.groovy.minimum) { exclude(module = "groovy-all") }
  "minimumGradleTestRuntimeOnly"(libs.junit.platform.launcher)
  "minimumGradleTestRuntimeOnly"(files(tasks.named("pluginUnderTestMetadata")))
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
