package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.reporter.JsonReporter
import com.github.benmanes.gradle.versions.reporter.XmlReporter
import com.github.benmanes.gradle.versions.reporter.result.DependenciesGroup
import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResult
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateResults
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for the counts of a result after a custom output formatter changes its dependencies.
 */
final class ResultCountSpec extends Specification {

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1149')
  def 'The counts are correct after a dependency is removed from a group'() {
    given:
    def result = resultWithCurrent('guava', 'guice')

    when:
    result.current.dependencies.removeIf { it.name == 'guice' }

    then:
    result.current.count == 1
    result.count == 1
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1149')
  def 'The json and xml reports are written with the counts after a removal'() {
    given:
    def result = resultWithCurrent('guava', 'guice')
    result.current.dependencies.removeIf { it.name == 'guice' }
    def json = new ByteArrayOutputStream()
    def xml = new ByteArrayOutputStream()

    when:
    new JsonReporter(':', 'milestone', 'release-candidate').write(new PrintStream(json), result)
    new XmlReporter(':', 'milestone', 'release-candidate').write(new PrintStream(xml), result)

    then:
    def parsedJson = new JsonSlurper().parseText(json.toString())
    parsedJson.count == 1
    parsedJson.current.count == 1
    def parsedXml = new XmlSlurper().parseText(xml.toString())
    parsedXml.count.text() == '1'
    parsedXml.current.count.text() == '1'
  }

  def 'A count passed to a group that disagrees with its dependencies is ignored'() {
    expect:
    new DependenciesGroup<Dependency>(5, [new Dependency('g', 'a', '1')] as Set).count == 1
  }

  private static Result resultWithCurrent(String... names) {
    Set<Dependency> current = names.collect { new Dependency('com.example', it, '1.0') } as Set
    def none = { new DependenciesGroup(0, [] as Set) }
    def channel = { new GradleUpdateResult(false, null, null) }
    new Result(
      current.size(),
      new DependenciesGroup<Dependency>(current.size(), current),
      none(), none(), none(), none(),
      new GradleUpdateResults(false, channel(), channel(), channel(), channel()))
  }
}
