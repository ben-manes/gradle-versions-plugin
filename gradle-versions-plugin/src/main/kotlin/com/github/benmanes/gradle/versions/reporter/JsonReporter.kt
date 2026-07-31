package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.AbsentWhenNull
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.gradle.api.Project
import java.io.OutputStream

/**
 * A JSON reporter for the dependency updates results.
 */
class JsonReporter(
  override val projectPath: String,
  override val revision: String,
  override val gradleReleaseChannel: String,
) : AbstractReporter(projectPath, revision, gradleReleaseChannel) {
  @Deprecated(
    "Use the constructor that takes the project's path.",
    ReplaceWith("JsonReporter(project.path, revision, gradleReleaseChannel)"),
  )
  constructor(
    project: Project,
    revision: String,
    gradleReleaseChannel: String,
  ) : this(project.path, revision, gradleReleaseChannel)

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
        .add(AbsentWhenNullAdapter())
        .addLast(KotlinJsonAdapterFactory())
        .build()
  }
}

/** Writes a JSON null without leaking the writer's serializeNulls setting to its caller. */
private fun JsonWriter.absentValue() {
  val serializeNulls = this.serializeNulls
  this.serializeNulls = false
  try {
    nullValue()
  } finally {
    this.serializeNulls = serializeNulls
  }
}

/** Omits an absent optional property that serializeNulls would otherwise write as null. */
private class AbsentWhenNullAdapter {
  @ToJson
  fun toJson(
    writer: JsonWriter,
    @AbsentWhenNull projects: List<String>?,
  ) {
    if (projects == null) {
      writer.absentValue()
    } else {
      writer.beginArray()
      for (project in projects) {
        writer.value(project)
      }
      writer.endArray()
    }
  }

  @FromJson
  @AbsentWhenNull
  fun fromJson(reader: JsonReader): List<String>? {
    val projects = mutableListOf<String>()
    reader.beginArray()
    while (reader.hasNext()) {
      projects.add(reader.nextString())
    }
    reader.endArray()
    return projects
  }

  @ToJson
  fun contributedToJson(
    writer: JsonWriter,
    @AbsentWhenNull contributed: Boolean?,
  ) {
    if (contributed == null) {
      writer.absentValue()
    } else {
      writer.value(contributed)
    }
  }

  @FromJson
  @AbsentWhenNull
  fun contributedFromJson(reader: JsonReader): Boolean? = reader.nextBoolean()
}
