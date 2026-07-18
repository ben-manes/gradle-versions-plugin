package com.github.benmanes.gradle.versions.updates

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the dependency version statuses observed by a single project.
 *
 * The statuses are resolved while the input is realized, so the metadata read during resolution is
 * tracked as a configuration cache input and a newly published version invalidates the entry.
 */
abstract class DependencyUpdatesPartialTask : DefaultTask() {
  @get:Input
  abstract val partialJson: Property<String>

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  init {
    description = "Resolves the dependency updates of a single project."
    group = "Help"
  }

  @TaskAction
  fun generate() {
    val file = outputFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText(partialJson.get())
  }
}
