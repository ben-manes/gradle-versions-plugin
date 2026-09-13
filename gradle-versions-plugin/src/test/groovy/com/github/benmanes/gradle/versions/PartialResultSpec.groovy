package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.ConstraintInfo
import com.github.benmanes.gradle.versions.updates.PartialResult
import com.github.benmanes.gradle.versions.updates.PartialResultKt
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.SkippedInfo
import com.github.benmanes.gradle.versions.updates.UnresolvedInfo
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
final class PartialResultSpec extends Specification {
  private static PartialStatus status(String name, String declaredVersion,
      UnresolvedInfo unresolved = null) {
    return new PartialStatus('com.google.guava', name, declaredVersion, null, '1.0', null,
      unresolved)
  }

  def 'Round trips through json'() {
    given:
    def unresolved = new UnresolvedInfo('com.google.guava', 'guava', '+', 'boom\n\tat here')
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':sub',
      [status('guava', '1.0'), status('gson', 'none', unresolved)], [status('okio', '2.0')])

    when:
    def decoded = PartialResult.fromJson(result.toJson())

    then:
    decoded == result
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
  def 'The observing project is not serialized'() {
    given:
    def status = new PartialStatus(
      'com.google.guava', 'guava', '1.0', null, '1.0', null, null, false, [], ':stamped-project')
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':', [status], [])

    when:
    def json = result.toJson()
    def decoded = PartialResult.fromJson(json)

    then:
    !json.contains('stamped-project')
    decoded.statuses[0].projectPath == null
  }

  def 'The split marker is not serialized'() {
    given:
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':', [status('guava', '1.0')], [])

    when:
    def json = result.toJson()

    then:
    !json.contains('splitByLatest')
    PartialResult.fromJson(json) == result
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1028')
  def 'A partial without the contributed key reads as declared'() {
    given:
    def json = '''
      {"formatVersion":1,"projectPath":":","statuses":[{"group":"com.google.guava",
      "name":"guava","declaredVersion":"1.0","latestVersion":"1.0"}],"buildscriptStatuses":[]}
      '''.stripIndent()

    when:
    def decoded = PartialResult.fromJson(json)

    then:
    decoded.statuses[0].contributed == false
    decoded.statuses[0].configurations == []
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1028')
  def 'The contributed flag survives the round trip'() {
    given:
    def status =
      new PartialStatus('com.google.guava', 'guava', '1.0', null, '1.0', null, null, true, ['tool'])
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':', [status], [])

    when:
    def json = result.toJson()
    def decoded = PartialResult.fromJson(json)

    then:
    json.contains('"contributed":true')
    decoded.statuses[0].contributed
    decoded.statuses[0].configurations == ['tool']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1043')
  def 'A partial without the declared unresolved version reads as none'() {
    given:
    def json = '''
      {"formatVersion":1,"projectPath":":","statuses":[{"group":"com.google.guava",
      "name":"guava","declaredVersion":"none","latestVersion":"none","unresolved":
      {"selectorGroup":"com.google.guava","selectorName":"guava","selectorVersion":"+",
      "failureText":"boom"}}],"buildscriptStatuses":[]}
      '''.stripIndent()

    when:
    def decoded = PartialResult.fromJson(json)

    then:
    decoded.statuses[0].unresolved.declaredVersion == 'none'
    decoded.statuses[0].unresolved.userReason == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'The platform projects survive the round trip'() {
    given:
    def status = new PartialStatus('com.example', 'external-bom', '1.0', null, '1.0', null, null,
      false, [], ':app', [':platforms'])
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':', [status], [])

    when:
    def json = result.toJson()
    def decoded = PartialResult.fromJson(json)

    then:
    json.contains('"platformProjects":[":platforms"]')
    decoded.statuses[0].platformProjects == [':platforms']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1070')
  def 'A partial without the platform projects key reads as none'() {
    given:
    def json = '''
      {"formatVersion":1,"projectPath":":","statuses":[{"group":"com.google.guava",
      "name":"guava","declaredVersion":"1.0","latestVersion":"1.0"}],"buildscriptStatuses":[]}
      '''.stripIndent()

    when:
    def decoded = PartialResult.fromJson(json)

    then:
    decoded.statuses[0].platformProjects == []
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'The constraining platforms survive the round trip'() {
    given:
    def status = new PartialStatus('com.google.inject', 'guice', '2.0', null, '3.1', null, null,
      false, [], ':app', [], [':platform', 'com.example:external-bom:1.0'])
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':', [status], [])

    when:
    def json = result.toJson()
    def decoded = PartialResult.fromJson(json)

    then:
    json.contains('"constrainedBy":[":platform","com.example:external-bom:1.0"]')
    decoded.statuses[0].constrainedBy == [':platform', 'com.example:external-bom:1.0']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'A partial without the constraining platforms key reads as none'() {
    given:
    def json = '''
      {"formatVersion":1,"projectPath":":","statuses":[{"group":"com.google.guava",
      "name":"guava","declaredVersion":"1.0","latestVersion":"1.0"}],"buildscriptStatuses":[]}
      '''.stripIndent()

    when:
    def decoded = PartialResult.fromJson(json)

    then:
    decoded.statuses[0].constrainedBy == []
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'A partial without the skipped key reads as none skipped'() {
    given:
    def json = '''
      {"formatVersion":1,"projectPath":":","statuses":[],"buildscriptStatuses":[]}
      '''.stripIndent()

    when:
    def decoded = PartialResult.fromJson(json)

    then:
    decoded.skipped == []
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/801')
  def 'Skipped configurations survive the round trip'() {
    given:
    def skipped = new SkippedInfo('compileClasspath', 'org.gradle.api.InvalidUserCodeException: boom')
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':', [], [], [skipped])

    when:
    def json = result.toJson()
    def decoded = PartialResult.fromJson(json)

    then:
    decoded == result
    json.contains('org.gradle.api.InvalidUserCodeException: boom')
  }

  def 'Rejects an unknown format version'() {
    given:
    def json = new PartialResult(99, ':', [], []).toJson()

    when:
    PartialResult.fromJson(json)

    then:
    thrown(IllegalArgumentException)
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'A v1 partial reads with no recorded candidates and no constraints'() {
    given:
    def json = '''
      {"formatVersion":1,"projectPath":":","statuses":[{"group":"com.google.guava",
      "name":"guava","declaredVersion":"1.0","latestVersion":"1.0"}],"buildscriptStatuses":[]}
      '''.stripIndent()

    when:
    def decoded = PartialResult.fromJson(json)

    then:
    decoded.candidates == []
    decoded.statuses[0].constraint == null
    decoded.statuses[0].platformConstraints == []
  }

  def 'A partial from before the embedded Kotlin marks reads as unmarked'() {
    given:
    def json = '{"formatVersion":3,"projectPath":":","statuses":[{"group":"org.jetbrains.kotlin",' +
      '"name":"kotlin-stdlib","declaredVersion":"2.4.0","latestVersion":"2.4.20"}],"buildscriptStatuses":[]}'

    when:
    def decoded = PartialResult.fromJson(json)

    then:
    !decoded.marksEmbeddedKotlin
    !decoded.statuses[0].embeddedKotlin
  }

  def 'The embedded Kotlin marks survive the round trip'() {
    given:
    def result = PartialResult.fromJson(
      '{"formatVersion":3,"projectPath":":","statuses":[{"group":"org.jetbrains.kotlin",' +
        '"name":"kotlin-stdlib","declaredVersion":"2.4.0","latestVersion":"2.4.20","embeddedKotlin":true}],' +
        '"buildscriptStatuses":[],"marksEmbeddedKotlin":true}')

    when:
    def decoded = PartialResult.fromJson(result.toJson())

    then:
    decoded == result
    decoded.marksEmbeddedKotlin
    decoded.statuses[0].embeddedKotlin
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'Rejects a format version newer than this reader supports'() {
    given:
    def json = new PartialResult(PartialResult.FORMAT_VERSION + 1, ':', [], []).toJson()

    when:
    PartialResult.fromJson(json)

    then:
    thrown(IllegalArgumentException)
  }

  def 'Reports the project with the unreadable partial, and the version skew behind it'() {
    given: 'a producer running a newer release than the report that reads its partial'
    def json = new PartialResult(PartialResult.FORMAT_VERSION + 1, ':child:sub', [], []).toJson()

    when:
    PartialResult.fromJson(json)

    then: 'the producer and the skew are both in the message, rather than advice to re-run'
    def e = thrown(IllegalArgumentException)
    e.message.contains(':child:sub')
    e.message.contains('newer version of the plugin')
    !e.message.contains('re-run the build')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/948')
  def 'The declared and platform-supplied constraints survive the round trip'() {
    given:
    def declared = new ConstraintInfo('[1.0,2.0)', '1.5', '1.8', ['1.9'])
    def platform = new ConstraintInfo('1.0', '', '', [])
    def status = new PartialStatus('com.google.guava', 'guava', '1.0', null, '1.0', null, null, false,
      [], null, [], [], false, declared, [platform])
    def result = new PartialResult(PartialResult.FORMAT_VERSION, ':', [status], [])

    when:
    def json = result.toJson()
    def decoded = PartialResult.fromJson(json)

    then:
    decoded == result
    decoded.statuses[0].constraint == declared
    decoded.statuses[0].platformConstraints == [platform]
  }

  @Unroll
  def 'Merges statuses by coordinate key: #scenario'() {
    expect:
    PartialResultKt.mergeStatuses(statuses)*.declaredVersion == expected

    where:
    scenario                      | statuses                                    || expected
    'distinct keys are kept'      | [status('guava', '1.0'), status('gson', '2.0')] || ['1.0', '2.0']
    'concrete displaces none'     | [status('guava', 'none'), status('guava', '1.0')] || ['1.0']
    'none does not displace'      | [status('guava', '1.0'), status('guava', 'none')] || ['1.0']
    'concrete does not displace'  | [status('guava', '1.0'), status('guava', '2.0')] || ['1.0', '2.0']
  }
}
