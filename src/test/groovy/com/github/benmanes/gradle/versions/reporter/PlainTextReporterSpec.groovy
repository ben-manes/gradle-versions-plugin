package com.github.benmanes.gradle.versions.reporter

import spock.lang.Specification

import static com.github.benmanes.gradle.versions.TestProjectTools.*

class PlainTextReporterSpec  extends Specification {
  def "Single Project - Milestone"() {
    setup:
    def project = singleProject()
    addDependenciesTo(project)
    addRepositoryTo(project)
    def result = evaluate(project, 'milestone', 'json,xml').buildBaseObject()
    def textReporter = new PlainTextReporter(project, 'milestone')
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream printStream = new PrintStream(outputStream, true)
    when:
    textReporter.write(printStream, result)
    then:
    outputStream.toString() == '''
------------------------------------------------------------
: Project Dependency Updates (report to plain text file)
------------------------------------------------------------

The following dependencies are using the latest milestone version:
 - backport-util-concurrent:backport-util-concurrent:3.1
     I said so
 - backport-util-concurrent:backport-util-concurrent-java12:3.1

The following dependencies exceed the version found at the milestone revision level:
 - com.google.guava:guava [99.0-SNAPSHOT <- 16.0-rc1]
     I know the future
 - com.google.guava:guava-tests [99.0-SNAPSHOT <- 16.0-rc1]

The following dependencies have later milestone versions:
 - com.google.inject:guice [2.0 -> 3.0]
     That's just the way it is
     http://code.google.com/p/google-guice/
 - com.google.inject.extensions:guice-multibindings [2.0 -> 3.0]
     http://code.google.com/p/google-guice/

Failed to determine the latest version for the following dependencies (use --info for details):
 - com.github.ben-manes:unresolvable
     Life is hard
 - com.github.ben-manes:unresolvable2
'''.replace('\r','').replace('\n', System.lineSeparator())
  }
}
