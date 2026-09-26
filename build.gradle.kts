import org.gradle.api.JavaVersion.VERSION_1_8
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka) apply false
  alias(libs.plugins.ktlint)
  alias(libs.plugins.plugin.publish) apply false
  alias(libs.plugins.versions)
  `java-gradle-plugin`
  `java-library`
  groovy
}

allprojects {
  configurations.configureEach {
    resolutionStrategy {
      preferProjectModules()

      enableDependencyVerification()
    }
  }
}

subprojects {
  tasks.withType<Jar>().configureEach {
    manifest {
      attributes(
        "Implementation-Title" to project.property("POM_NAME").toString(),
        "Implementation-Version" to project.property("VERSION_NAME").toString(),
        "Built-By" to System.getProperty("user.name"),
        "Built-JDK" to System.getProperty("java.version"),
        "Built-Gradle" to gradle.gradleVersion,
      )
    }
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JVM_1_8)
      // The build runs on a JDK far newer than the target, so bound the API as well as the
      // bytecode. The JDK 8 matrix row used to give this by compiling on JDK 8 itself.
      freeCompilerArgs.add("-Xjdk-release=1.8")
      // Kotlin code compiled against the plugin, such as a precompiled script plugin in buildSrc,
      // is compiled by the Kotlin embedded in the running Gradle, which reads metadata at most one
      // release newer than its own. Gradle 8.4 embeds Kotlin 1.9, so the language version is raised
      // only with the oldest supported Gradle, despite the compiler's deprecation warning.
      languageVersion.set(KOTLIN_2_0)
    }
  }

  tasks.withType<JavaCompile>().configureEach {
    options.apply {
      release.set(VERSION_1_8.majorVersion.toInt())
      compilerArgs = compilerArgs +
        listOf(
          "-Xlint:all",
          "-Xlint:-processing",
        )
      encoding = "utf-8"
      isFork = true
    }
  }

  tasks.withType<GroovyCompile>().configureEach {
    sourceCompatibility = VERSION_1_8.toString()
    targetCompatibility = VERSION_1_8.toString()

    options.apply {
      compilerArgs = compilerArgs +
        listOf(
          "-Xlint:all",
          "-Xlint:-processing",
        )
      encoding = "utf-8"
      isFork = true
    }
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform() // Ensure JUnit Platform is used if you are using JUnit 5 or Spock 2.x

    testLogging {
      exceptionFormat = FULL
      showCauses = true
      showExceptions = true
      showStackTraces = true
      showStandardStreams = true
      events = setOf(PASSED, FAILED, SKIPPED)
    }

    // A path relative to the project directory, which is the worker's working directory, so that
    // the test task's inputs are the same in every checkout.
    systemProperty("testKitPool", ".gradle/testkit/$name")
    maxParallelForks = (gradle.startParameter.maxWorkerCount / 2).coerceIn(1, 4)
  }
}
