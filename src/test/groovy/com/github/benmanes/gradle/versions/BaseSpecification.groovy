package com.github.benmanes.gradle.versions

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class BaseSpecification extends Specification {
  @Rule TemporaryFolder testProjectDir = new TemporaryFolder()
  File buildFile
  List<File> pluginClasspath

  def 'setup'() {
    buildFile = testProjectDir.newFile('build.gradle')
  }
}
