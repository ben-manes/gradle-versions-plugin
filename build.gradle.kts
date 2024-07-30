import org.gradle.api.JavaVersion.VERSION_1_8
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
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
        "Implementation-Title" to properties["POM_NAME"],
        "Implementation-Version" to properties["VERSION_NAME"],
        "Built-By" to System.getProperty("user.name"),
        "Built-JDK" to System.getProperty("java.version"),
        "Built-Gradle" to gradle.gradleVersion,
      )
    }
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JVM_1_8)
    }
  }

  tasks.withType<JavaCompile>().configureEach {
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
    testLogging {
      exceptionFormat = FULL
      showCauses = true
      showExceptions = true
      showStackTraces = true
      showStandardStreams = true
      events = setOf(PASSED, FAILED, SKIPPED)
    }

    val maxWorkerCount = gradle.startParameter.maxWorkerCount
    maxParallelForks = if (maxWorkerCount < 2) 1 else maxWorkerCount / 2
  }
}
