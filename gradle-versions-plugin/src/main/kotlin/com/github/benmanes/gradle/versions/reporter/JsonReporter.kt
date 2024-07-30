package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.squareup.moshi.Moshi
import org.gradle.api.Project
import java.io.OutputStream

/**
 * A JSON reporter for the dependency updates results.
 */
class JsonReporter(
  override val project: Project,
  override val revision: String,
  override val gradleReleaseChannel: String,
) : AbstractReporter(project, revision, gradleReleaseChannel) {
  override fun write(
    printStream: OutputStream,
    result: Result,
  ) {
    val jsonAdapter =
      moshi
        .adapter(Result::class.java)
        .serializeNulls()
        .indent(" ")
    val json = jsonAdapter.toJson(result).trimMargin()
    printStream.println(json)
  }

  override fun getFileExtension(): String {
    return "json"
  }

  companion object {
    private val moshi =
      Moshi.Builder()
        .build()
  }
}
