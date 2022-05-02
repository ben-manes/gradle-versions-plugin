package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/**
 * A json reporter for the dependency updates results.
 */
@CompileStatic
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class JsonReporter extends AbstractReporter {
  private static final Moshi moshi = new Moshi.Builder()
    .addLast(new KotlinJsonAdapterFactory())
    .build()

  @Override
  void write(Appendable printStream, Result result) {
    JsonAdapter<Result> jsonAdapter = moshi
      .adapter(Result.class)
      .serializeNulls()
      .indent(" ")
    String json = jsonAdapter.toJson(result).stripMargin()
    printStream.println(json)
  }

  @Override
  String getFileExtension() {
    return "json"
  }
}
