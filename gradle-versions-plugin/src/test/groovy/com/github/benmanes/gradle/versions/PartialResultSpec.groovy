package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.PartialResult
import com.github.benmanes.gradle.versions.updates.PartialResultKt
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.UnresolvedInfo
import spock.lang.Specification
import spock.lang.Unroll

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

  def 'Rejects an unknown format version'() {
    given:
    def json = new PartialResult(99, ':', [], []).toJson()

    when:
    PartialResult.fromJson(json)

    then:
    thrown(IllegalArgumentException)
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
