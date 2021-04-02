package com.github.benmanes.gradle.versions.parser

import groovy.transform.CompileStatic

/**
 * Parser targeted for analyzing gradle build files.
 */
@CompileStatic
abstract class BuildGradleParser {

  protected File file
  protected String content

  private BuildGradleParser() {
  }

  protected BuildGradleParser(final File file) {
    if (!file.exists()) {
      throw new FileNotFoundException("Can not find file ${file.getPath()}")
    }
    this.file = file
    this.content = file.getText()
  }

  /**
   * Get the parsed file.
   * @return File.
   */
  File getFile() {
    return file
  }

  /**
   * Get the content of the gradle build file.
   * @return Gradle build file content.
   */
  String getContent() {
    return content
  }

  /**
   * Get the project version expression, exactly as defined in the gradle build file.
   * @return Project version expression, i.e. 'version=project.findProperty("projectVersion").toString()',
   * 'version "1.0.0"' ...
   */
  abstract String getVersionExpression()

  /**
   * Get the project version definition, after java class generation.
   * @return Project version definition.
   */
  abstract String getVersionDefinition()
}
