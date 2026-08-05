package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A component selection rule can only respect what the build declared if the declared
 * {@code VersionConstraint} reaches the closure. Without it the rule has to restate in the build
 * script a bound that is already stated in the declaration.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
final class DeclaredVersionConstraintSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String reportFolder
  private String mavenRepoUrl

  private String classpathString

  def 'setup'() {
    reportFolder = "${testProjectDir.root.path.replaceAll('\\\\', '/')}/build/dependencyUpdates"
    mavenRepoUrl = getClass().getResource('/maven/').toURI()

    def pluginClasspathResource = getClass().classLoader.getResource('plugin-classpath.txt')
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        'Did not find plugin classpath resource, run `testClasses` build task.')
    }
    classpathString = pluginClasspathResource.readLines()
      .collect { it.replace('\\', '\\\\') }
      .collect { "'$it'" }
      .join(', ')
  }

  private void writeBuildFile(String declarations, String taskBody) {
    testProjectDir.newFile('build.gradle') <<
      """
        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          $declarations
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
          $taskBody
        }
      """.stripIndent()
  }

  private def run() {
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    assert result.task(':dependencyUpdates').outcome == SUCCESS
    return result
  }

  private def report() {
    return new JsonSlurper().parseText(new File(reportFolder, 'report.json').text)
  }

  def 'every declared field reaches the selection rule verbatim'() {
    given: 'one module rejecting an exact version, one rejecting a range'
    writeBuildFile(
      """
        api('com.google.inject:guice') {
          version {
            strictly '[2.0, 3.1['
            reject '2.2'
          }
        }
        api('com.google.guava:guava') {
          version {
            require '15.0'
            reject '[16.0,)'
          }
        }
      """,
      """
        rejectVersionIf {
          println "PROBE \${candidate.module} strict='\${versionConstraint?.strictVersion}'" +
            " required='\${versionConstraint?.requiredVersion}'" +
            " rejects=\${versionConstraint?.rejectedVersions}"
          return false
        }
      """)

    when:
    def result = run()

    then: 'the bound the build declared, not the "+" the query substitutes for it'
    result.output.contains("PROBE guice strict='[2.0, 3.1[' required='[2.0, 3.1[' rejects=[2.2]")

    and: 'a reject comes back as the selector that was declared, so a range arrives unexpanded'
    result.output.contains("PROBE guava strict='' required='15.0' rejects=[[16.0,)]")
  }

  def 'the README recipe does not offer a version the build rejected'() {
    given: 'guice rejects its own newest version, guava rejects nothing'
    writeBuildFile(
      """
        api('com.google.inject:guice') {
          version {
            require '2.0'
            reject '3.1'
          }
        }
        api 'com.google.guava:guava:15.0'
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'guice stops below the version it rejects, guava is unaffected'
    report().outdated.dependencies*.name == ['guava', 'guice']
    report().outdated.dependencies.find { it.name == 'guice' }.available.milestone == '3.0'
    report().outdated.dependencies.find { it.name == 'guava' }.available.milestone == '16.0-rc1'
    report().unresolved.dependencies.isEmpty()
  }

  def 'without the rule the rejected version is offered, so the rule is what changes it'() {
    given:
    writeBuildFile(
      """
        api('com.google.inject:guice') {
          version {
            require '2.0'
            reject '3.1'
          }
        }
      """,
      '')

    when:
    run()

    then: 'the unbounded query offers the very version the build rejects'
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.1'
  }

  def 'the README recipe compiles and applies under the Kotlin DSL'() {
    given:
    testProjectDir.newFile('build.gradle.kts') <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          `java-library`
          id("io.github.ben-manes.versions")
        }

        repositories {
          maven(url = "${mavenRepoUrl}")
        }

        dependencies {
          api("com.google.inject:guice") {
            version {
              require("2.0")
              reject("3.1")
            }
          }
        }

        tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
          outputFormatter = "json"
          checkForGradleUpdate = false
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()

    when:
    run()

    then:
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.0'
  }

  def 'a declared range bounds the report, and an in-range upgrade is still offered'() {
    given: 'guice resolves to 2.0, while 2.1, 2.2 and 3.0 are inside the range and 3.1 is not'
    writeBuildFile(
      """
        api 'com.google.inject:guice:2.0'
        constraints {
          api('com.google.inject:guice') {
            version {
              strictly '[2.0, 3.1['
            }
          }
        }
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'the top of the declared range, not the 3.1 the unbounded query finds'
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.0'
    report().unresolved.dependencies.isEmpty()
  }

  def 'a module with no declared bound is not held back'() {
    given: 'a plain declaration is a floor resolution may rise above, not a bound'
    writeBuildFile(
      """
        api 'com.google.guava:guava:15.0'
        api 'com.google.inject:guice:2.0'
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'every upgrade still shows; bounding on a bare require would empty the report'
    report().outdated.dependencies*.name == ['guava', 'guice']
    report().outdated.dependencies.find { it.name == 'guava' }.available.milestone == '16.0-rc1'
    report().outdated.dependencies.find { it.name == 'guice' }.available.milestone == '3.1'
  }

  def 'a rejected range is honored, not compared as a string'() {
    given: 'the rejected range names no version literally, so a string comparison would miss it'
    writeBuildFile(
      """
        api('com.google.inject:guice') {
          version {
            require '2.0'
            reject '[3.1,)'
          }
        }
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: '3.1 falls inside the rejected range, so 3.0 is the offer'
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.0'
  }

  // The bound is read with the parser dependency resolution uses, which Gradle does not publish, so
  // the ends of the supported range are pinned. The versions between them do not move it
  // independently of these two.
  @IgnoreIf({ data.gradleVersion.startsWith('9') && !jvm.java17Compatible })
  @Unroll
  def 'a declared range is read the same way on Gradle #gradleVersion'() {
    given:
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java-library'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url = '${mavenRepoUrl}'
          }
        }

        dependencies {
          api('com.google.inject:guice') {
            version {
              strictly '[2.0, 3.1['
            }
          }
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withGradleVersion(gradleVersion)
      .build()

    then: '3.1 sits outside the declared range on both, so the report stops at 3.0'
    result.output.contains('com.google.inject:guice')
    !result.output.contains('-> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    gradleVersion << ['8.4', '9.6.1']
  }

  def 'a bound naming an unpublished version loses the report line to the unresolved section'() {
    given: 'guice 2.1 is listed in the repository metadata but was never published'
    writeBuildFile(
      """
        api('com.google.inject:guice') {
          version {
            strictly '2.1'
          }
        }
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'every candidate is rejected, which does not fail the build but does move the entry'
    report().outdated.dependencies.isEmpty()
    report().current.dependencies.isEmpty()
    report().unresolved.dependencies*.name == ['guice']
  }

  def 'a rule cannot rewrite the constraint the build declared'() {
    given: 'the rule is handed a copy, so mutating it must not change what the report resolves'
    writeBuildFile(
      """
        api('com.google.inject:guice') {
          version {
            strictly '[2.0, 3.1['
          }
        }
      """,
      """
        rejectVersionIf {
          try {
            versionConstraint?.strictly('9.9')
            println "PROBE-MUTATED"
          } catch (Exception e) {
            println "PROBE-REFUSED \${e.getClass().simpleName}"
          }
          return false
        }
      """)

    when:
    def result = run()

    then: 'the mutation is refused, and the reported current version is the resolved one'
    result.output.contains('PROBE-REFUSED')
    !result.output.contains('PROBE-MUTATED')
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].version == '3.0'
  }

  def 'a substituted module has no declared constraint, and the recipe tolerates it'() {
    given: 'the substituted module is keyed differently than the declaration, so nothing matches it'
    writeBuildFile(
      "api 'com.google.guava:guava:15.0'",
      """
        rejectVersionIf {
          println "PROBE \${candidate.module} constraint=\${versionConstraint}"
          return versionConstraint?.rejectedVersions?.contains(candidate.version)
        }
      """)
    testProjectDir.root.toPath().resolve('build.gradle').append(
      """
        configurations.all {
          resolutionStrategy.dependencySubstitution {
            substitute module('com.google.guava:guava') using module('com.google.inject:guice:2.0')
          }
        }
      """.stripIndent())

    when:
    def result = run()

    then: 'the rule sees a null constraint and the build does not fail on it'
    result.output.contains('constraint=null')
  }
}
