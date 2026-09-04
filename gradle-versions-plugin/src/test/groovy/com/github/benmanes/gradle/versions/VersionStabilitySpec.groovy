package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.VersionStability
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A specification for the pre-release predicate. The cases are drawn from a survey of every
 * published version of 1,870 widely used artifacts on Maven Central, Google's Maven repository,
 * Clojars and the Gradle Plugin Portal.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/440
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
final class VersionStabilitySpec extends Specification {
  @Unroll
  def 'a pre-release marker is matched in #version'() {
    expect:
    VersionStability.isPreRelease(version)

    where:
    version << [
      '2.4.20-Beta2',
      '1.0-rc1',
      '3.0.0-M1',
      '3.0.0-RC1',
      '1.0.0-alpha01',
      '1.0.0-beta-1',
      '4.0.0.CR1',
      '1.1-ea',
      '2.1-EA1',
      '17.0.0-dev',
      '2.5-SNAPSHOT',
      '2.5-snapshot',
      '2.5-20240101.120000-1',
      '3.2.0rc2',
      '2.10.0.pr1',
      '3.0.0-milestone-3',
      '1.0.0-preview',
      '3.7.0-NIGHTLY',
    ]
  }

  @Unroll
  def 'a qualified release is left alone: #version'() {
    expect:
    !VersionStability.isPreRelease(version)

    where:
    // Every one of these is a shipped release with a platform, patch level or cross-build
    // qualifier. A stable-version pattern has to list each of them; this predicate matches known
    // markers and lets everything else through.
    version << [
      '1.0.0',
      'v1.2.3',
      '10.2.0.jre11',
      '12.10.0.jre8',
      '33.6.0-jre',
      '33.6.0-android',
      '0.10.0-hadoop1',
      '1.1.33.android',
      '1.1.31.sec01',
      '2.5.6.SEC03',
      '9.2-1002-jdbc4',
      '0.4-groovy-1.6',
      '2.3-groovy-4.0',
      '1.1.17.SP2',
      '2.0.10.graal',
      '1.1.33.Fork13',
      '1.6.3-native-mt',
      '2.4.1-scalaz-7.0.6',
      '1.21-R0.4',
      '5.13.0.202109080827-r',
      '1.0.0.Final',
      '5.3.9.RELEASE',
      '4.2.0.GA_CP01',
      '1.0.0+build.5',
      '2.0.0-deprecated-use-gradle-api',
      // The most dangerous case in the survey: a final Jackson release with a qualifier ending in
      // a capital M and a number. A marker rule allowing a letter to the left of `m` would match
      // `KotlinM11` as milestone 11 and hide a shipped release.
      '2.5.1.1.KotlinM11',
      // The one-letter marker after a digit: an OpenSSL letter release, as the bytedeco presets
      // publish it. A marker rule allowing a digit to the left of `m` would hide it.
      '1.1.1m-1.5.7',
      '0.9.1.space',
    ]
  }

  @Unroll
  def 'a qualified pre-release is still a pre-release: #version'() {
    expect:
    VersionStability.isPreRelease(version)

    where:
    // A cross-build or platform qualifier does not cancel a pre-release marker beside it, and
    // `0.9.2-pre2-RELEASE` is matched as stable outright by the README recipe.
    version << ['22.0-rc1-android', '2.4-M7-groovy-4.0', '13-ea+14b', '26-ea+11', '0.9.2-pre2-RELEASE']
  }

  @Unroll
  def 'a marker inside a longer word is not a marker: #version'() {
    expect:
    !VersionStability.isPreRelease(version)

    where:
    // `pre` in `prefetch`, `m` in `mysql`, `rc` in `arch`, `dev` in `device` and `development`. A
    // substring match on the marker would flag every one of these, and so does the README recipe,
    // since none of them matches its stable pattern.
    version << ['1.0-prefetch', '1.0-mysql', '1.0-march', '1.0-device', '1.0-development-kit']
  }

  @Unroll
  def 'a version ending in a commit hash is something CI published: #version'() {
    expect:
    VersionStability.isPreRelease(version)

    where:
    version << ['0.10-ea8fbc9', '2.11.8-18269ea', '3.6-bb17ea2', '0.1-d1b5231',
                '1.1.0-4dd6c85cab1ef1a4415abb74704d60e57497b7b8',
                '0.0.0-2022-12-12T06-32-18-ed7ddf78']
  }

  @Unroll
  def 'a release the Apache Incubator requires to be marked incubating is still a release: #version'() {
    expect:
    // A podling has to ship `-incubating`, so matching it as a marker would leave the release
    // out. https://incubator.apache.org/guides/releasemanagement.html
    !VersionStability.isPreRelease(version)

    where:
    version << ['1.1-incubating', '2.0.0-incubating', '2.0.0.incubating', '0.7.0-incubating']
  }

  @Unroll
  def 'build metadata has no precedence, so nothing after a plus is a pre-release: #version'() {
    expect:
    // https://semver.org/#spec-item-10
    !VersionStability.isPreRelease(version)

    where:
    version << ['1.0.0+build.1a2b3c4', '1.0.0+1a2b3c4', '2.3.4+d6c85cab1ef1a44', '1.2.3+alpha.1']
  }

  @Unroll
  def 'a trailing run of digits is a build number rather than a hash, so it is left alone: #version'() {
    expect:
    // The hash rule requires at least one a-f. Without that it would fire on any long build
    // number, and some of those are releases.
    !VersionStability.isPreRelease(version)

    where:
    version << ['2.0.0-1234567', '1.0-20240315', '9.2-1002-jdbc4', '2.8.0.20240101']
  }

  def 'a convention no general marker set has is passed through, not rejected'() {
    expect: 'ActiveMQ Artemis spells an alpha milestone `.AM27`, and graphql-java stamps a date'
    !VersionStability.isPreRelease('2.0.0.AM27')
    !VersionStability.isPreRelease('230521-nf-execution')

    and: 'so an upgrade to one is reported rather than hidden, which is the fail-open direction'
    !VersionStability.isLessStable('2.0.0.AM27', '2.0.0')
    !VersionStability.isLessStable('230521-nf-execution', '221101-nf-execution')
  }

  @Unroll
  def 'an upgrade from #current to #candidate is rejected: #rejected'() {
    expect:
    VersionStability.isLessStable(candidate, current) == rejected

    where:
    current          | candidate          || rejected
    '1.0.0'          | '2.0.0-rc1'        || true
    '1.0.0'          | '2.0.0'            || false
    // Already on a pre-release, so the next one is reported.
    '2.0.0-rc1'      | '2.0.0-rc2'        || false
    '2.0.0-rc1'      | '2.0.0'            || false
    '9.7.0-rc-1'     | '9.7.0-rc-2'       || false
    '1.0-SNAPSHOT'   | '2.0-SNAPSHOT'     || false
    // A release variant is a release on both sides, so nothing is withheld either way.
    '10.2.0.jre8'    | '10.2.0.jre11'     || false
    '1.1.17.SP1'     | '1.1.17.SP2'       || false
  }
}
