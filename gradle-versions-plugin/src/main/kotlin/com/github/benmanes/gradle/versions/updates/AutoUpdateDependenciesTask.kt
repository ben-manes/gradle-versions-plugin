package com.github.benmanes.gradle.versions.updates

import com.google.gson.Gson
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class AutoUpdateDependenciesTask : DefaultTask() {
  @TaskAction
  fun updateDependencies() {
    val reportFile = File(project.buildDir, "dependencyUpdates/report.json")
    if (!reportFile.exists()) {
      println("Dependency report not found. Run 'dependencyUpdates' task first.")
      return
    }

    val gson = Gson()
    val json = gson.fromJson(reportFile.readText(), DependencyReport::class.java)
    val updates = mutableMapOf<String, String>()

    // Get update rules from Gradle properties
    val allowMajorUpdates = project.findProperty("allowMajorUpdates")?.toString()?.toBoolean() ?: false
    val allowMinorUpdates = project.findProperty("allowMinorUpdates")?.toString()?.toBoolean() ?: true
    val ignoredGroups = project.findProperty("ignoredGroups")?.toString()?.split(",") ?: listOf("com.example", "org.springframework")

    json.outdated.dependencies.forEach { dep ->
      if (dep.group in ignoredGroups) return@forEach

      val currentVersion = dep.version.split(".")
      val latestVersion = dep.available.release?.split(".") ?: currentVersion

      val isMajorUpdate = currentVersion[0] != latestVersion[0]
      val isMinorUpdate = currentVersion[1] != latestVersion[1] && currentVersion[0] == latestVersion[0]

      if ((!allowMajorUpdates && isMajorUpdate) || (!allowMinorUpdates && isMinorUpdate)) return@forEach

      updates["${dep.group}:${dep.name}"] = dep.available.release ?: "Unknown"
    }

    if (updates.isEmpty()) {
      println("No dependencies need updating.")
      return
    }

    val gradleFile = File(project.rootDir, "build.gradle.kts")
    var content = gradleFile.readText()

    updates.forEach { (dep, newVersion) ->
      val regex = """implementation\("($dep):([^"]+)"\)""".toRegex()
      content =
        regex.replace(content) { match ->
          "implementation(\"${match.groupValues[1]}:$newVersion\")"
        }
    }

    gradleFile.writeText(content)
    println("Dependencies updated successfully!")
  }
}
