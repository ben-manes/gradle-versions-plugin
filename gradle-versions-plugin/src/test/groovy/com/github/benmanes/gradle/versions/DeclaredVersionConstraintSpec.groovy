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
 * script a bound that is already written in the declaration.
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

  private def run(String... options) {
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(['dependencyUpdates'] + options.toList())
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

  def 'a version the build rejected is not offered under the README recipe'() {
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

  def 'the declared bound is respected without a rule, as the task property is on by default'() {
    given: 'a guice declaration rejecting its own newest version, with no rule beside it'
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

    then: 'guice stops below the rejected version, which the README recipe used to be needed for'
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.0'
  }

  def 'a rule reading satisfiesDeclaredBound is warned once per project that it is deprecated'() {
    given: 'a script classpath and two modules, so the rule is evaluated by both resolutions'
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          dependencies {
            constraints {
              classpath 'com.google.guava:guava:15.0'
            }
          }
        }

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
          api('com.google.inject:guice') {
            version {
              require '2.0'
              reject '3.1'
            }
          }
          api 'com.google.guava:guava:15.0'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          checkBuildEnvironmentConstraints = true
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()

    when:
    def result = run()

    then: 'one warning, not one per candidate and not one per resolution'
    result.output.count('satisfiesDeclaredBound is deprecated') == 1
  }

  def 'the default bound filter reads no deprecated member, so nothing is warned'() {
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
    def result = run()

    then:
    !result.output.contains('satisfiesDeclaredBound is deprecated')
  }

  def 'a constraint stating a range still bounds the report, though its version reads as a range'() {
    given: 'a constraint with no declaration beside it, so the row reports the range as its version'
    writeBuildFile(
      """
        constraints {
          api('com.google.inject:guice') {
            version {
              strictly '[2.0, 3.0['
            }
          }
        }
      """,
      'checkConstraints = true')

    when:
    run()

    then: 'the top of the declared range, not the 3.1 the unbounded query finds'
    def guice = report().outdated.dependencies.find { it.name == 'guice' }
    guice != null
    guice.available.milestone == '2.2'
  }

  def 'a constraint stating a single-version range still bounds the report'() {
    given: 'a strictly written as [2.0], which parses to the exact version but reads as a range'
    writeBuildFile(
      """
        constraints {
          api('com.google.inject:guice') {
            version {
              strictly '[2.0]'
            }
          }
        }
      """,
      'checkConstraints = true')

    when:
    run()

    then: 'the pinned version, not the 3.1 the unbounded query finds'
    def guice = report().outdated.dependencies.find { it.name == 'guice' }
    guice != null
    guice.available.milestone == '2.0'
  }

  def 'rejectOutOfBoundVersions = false offers the version the build rejects'() {
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
      'rejectOutOfBoundVersions = false')

    when:
    run()

    then: 'the unbounded query offers the very version the build rejects'
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.1'
  }

  def 'the command line option shows what the bound withholds for a single run'() {
    given: 'the build leaves the property at its default'
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
    run('--no-reject-out-of-bound-versions')

    then: 'the version the bound withholds is offered'
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

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
  def 'a required range does not bound a module a conflict pushed past it'() {
    given: 'guice is declared as a range, and a transitive requires a version above that range'
    writeBuildFile(
      """
        api 'com.google.inject:guice:[2.0, 3.0['
        api 'com.example:guice-consumer:1.0'
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'a required version is a floor resolution may rise above, a range included'
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.1'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
  def 'a script classpath does not report the version it resolved as a downgrade'() {
    given: 'a classpath range a transitive requires more than, so resolution rises above it'
    // The versionless guava declaration is what makes the copy resolve transitively, which is the
    // only way guice-consumer's requirement can push guice past its range. Drop it and the report
    // never reaches the case below.
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          configurations.create('probeClasspath')
          dependencies {
            constraints {
              probeClasspath 'com.google.guava:guava:15.0'
            }
            probeClasspath 'com.google.guava:guava'
            probeClasspath 'com.google.inject:guice:[2.0, 3.0['
            probeClasspath 'com.example:guice-consumer:1.0'
          }
        }

        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
          rejectVersionIf {
            !satisfiesDeclaredBound
          }
        }
      """.stripIndent()

    when:
    def result = run()

    then: 'the selected version is in bound even where the interval alone would exclude it'
    !result.output.contains('com.google.inject:guice [3.0 <- ')
    result.output.contains(' - com.google.inject:guice:3.0')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/755')
  def 'a dynamic classpath bound the resolved version already exceeds does not hold the report'() {
    given: 'the same classpath range resolution rose above, with no rule to reject with'
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
          configurations.create('probeClasspath')
          dependencies {
            constraints {
              probeClasspath 'com.google.guava:guava:15.0'
            }
            probeClasspath 'com.google.guava:guava'
            probeClasspath 'com.google.inject:guice:[2.0, 3.0['
            probeClasspath 'com.example:guice-consumer:1.0'
          }
        }

        plugins {
          id 'java-library'
          id 'io.github.ben-manes.versions'
        }

        tasks.named('dependencyUpdates').configure {
          checkForGradleUpdate = false
        }
      """.stripIndent()

    when:
    def result = run()

    then: 'a bound the resolved version already sits above holds nothing, so the upgrade stands'
    result.output.contains('com.google.inject:guice [3.0 -> 7.0.0]')
  }

  def 'a module with no declared bound is not bounded'() {
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
    gradleVersion << ['8.4', GradleVersions.CURRENT]
  }

  def 'a bound on an unpublished version moves the report line to the unresolved section'() {
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
    report().current.dependencies*.name == ['guice']
    report().current.dependencies[0].version == '3.0'
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

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  @Unroll
  def 'a module the platform supplies the version for is bounded by the platform, #label'() {
    given: 'log4j-core is declared with no version and its version comes from the platform'
    writeBuildFile(
      """
        api $platform
        api 'org.apache.logging.log4j:log4j-core'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'the platform-supplied module is held to the platform, so it reports as current'
    report().current.dependencies*.name.contains('log4j-core')
    !report().outdated.dependencies*.name.contains('log4j-core')

    and: 'a platform declared as an external module still reports its own available upgrade'
    (platformReportsOwnUpgrade
      ? report().outdated.dependencies*.name.contains('log4j')
      : !report().outdated.dependencies*.name.contains('log4j'))

    where:
    platform                                                    | platformReportsOwnUpgrade | label
    "platform('org.apache.logging.log4j:log4j:2.16.0')"         | true                       | 'platform()'
    "enforcedPlatform('org.apache.logging.log4j:log4j:2.16.0')" | false                      | 'enforcedPlatform()'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'with the bound turned off the platform-supplied module is offered every upgrade'() {
    given: 'the bounded declarations above, with the property turned off'
    writeBuildFile(
      """
        api platform('org.apache.logging.log4j:log4j:2.16.0')
        api 'org.apache.logging.log4j:log4j-core'
      """,
      'rejectOutOfBoundVersions = false')

    when:
    run()

    then: 'the platform and the module it supplies both still report their upgrades'
    report().outdated.dependencies*.name.containsAll(['log4j', 'log4j-core'])
    !report().current.dependencies*.name.contains('log4j-core')
  }

  // A project platform's own module is aggregated into the same build, so its own view of
  // log4j-core (undeclared, unbounded from its own perspective) reaches the merged report too and
  // the module name is identical: the current/outdated JSON split above cannot isolate the
  // consumer's bounded view from the platform's own. Verify this case through the rule directly
  // instead, as 'the platform constraints reach the rule' does, and via the range check below,
  // which discriminates the consumer's entry from the platform's by declared version text.
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a project platform still bounds the module its own build declares no version for'() {
    given:
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api 'org.apache.logging.log4j:log4j-core:2.16.0'
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api platform(project(':platform'))
        api 'org.apache.logging.log4j:log4j-core'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          if (candidate.module == 'log4j-core' && currentVersion == '2.16.0') {
            println "PROBE \${candidate.module}@\${candidate.version} bound=\${satisfiesDeclaredBound}" +
              " platformConstraints=\${platformVersionConstraints.collect { it.requiredVersion }}"
          }
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = run()

    then: 'the consumer-declared, platform-bounded candidate is accepted only at the platform version'
    result.output.contains('PROBE log4j-core@2.16.0 bound=true platformConstraints=[2.16.0]')
    result.output.contains('PROBE log4j-core@2.17.0 bound=false platformConstraints=[2.16.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a constraint in an ordinary library does not bound the module'() {
    given: 'the same constraint shape as above, stated by a java-library rather than a platform'
    testProjectDir.newFile('settings.gradle') << "include 'lib'\n"
    testProjectDir.newFolder('lib')
    testProjectDir.newFile('lib/build.gradle') <<
      """
        plugins { id 'java-library' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api 'org.apache.logging.log4j:log4j-core:2.16.0'
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api project(':lib')
        api 'org.apache.logging.log4j:log4j-core'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          if (candidate.module == 'log4j-core') {
            println "PROBE \${candidate.module}@\${candidate.version} bound=\${satisfiesDeclaredBound}" +
              " platformConstraints=\${platformVersionConstraints.collect { it.requiredVersion }}"
          }
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = run()

    then: "the library's constraint supplies the version, but no platform bound reaches the rule"
    result.output.contains('PROBE log4j-core@2.17.0 bound=true platformConstraints=[]')

    and: 'no configuration surfaced the library constraint as a platform bound'
    !result.output.contains('platformConstraints=[2.16.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a range a platform declares still admits an in-range upgrade'() {
    given: 'the platform bounds guice to [2.0, 3.1), which admits 3.0 but not 3.1'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api('com.google.inject:guice') {
              version {
                require '[2.0, 3.1['
                prefer '2.0'
              }
            }
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api platform(project(':platform'))
        api 'com.google.inject:guice'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'the in-range upgrade is offered, the out-of-range one is not'
    def guice = report().outdated.dependencies.find { it.name == 'guice' && it.version == '2.0' }
    guice != null
    guice.available.milestone == '3.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'the platform constraints reach the rule'() {
    given: 'a versionless module bound by a platform, and the platform itself'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api 'org.apache.logging.log4j:log4j-core:2.16.0'
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api platform('org.apache.logging.log4j:log4j:2.16.0')
        api 'org.apache.logging.log4j:log4j-core'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          println "PROBE \${candidate.module} platformConstraints=" +
            "\${platformVersionConstraints.collect { it.requiredVersion }}"
          return false
        }
      """)

    when:
    def result = run()

    then: 'the bounded module carries the platform-required version, the platform carries none'
    result.output.contains('PROBE log4j-core platformConstraints=[2.16.0]')
    result.output.contains('PROBE log4j platformConstraints=[]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a version the platform did not choose is not rejected'() {
    given: 'a transitive requirement outbids the platform, so the merged selection sits above it'
    writeBuildFile(
      """
        api platform('org.apache.logging.log4j:log4j:2.16.0')
        api 'org.apache.logging.log4j:log4j-core'
        api 'com.example:log4j-consumer:1.0'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'the version resolution actually chose is reported as current, not falsely unresolved'
    report().current.dependencies*.name.contains('log4j-core')
    report().current.dependencies.find { it.name == 'log4j-core' }.version == '2.17.0'
    report().unresolved.dependencies.isEmpty()
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a candidate must satisfy every consumed platform, not just one of them'() {
    given: 'one platform admits a range up to 3.1, a second pins the module at 2.0'
    testProjectDir.newFile('settings.gradle') << "include 'p1', 'p2'\n"
    testProjectDir.newFolder('p1')
    testProjectDir.newFile('p1/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api('com.google.inject:guice') {
              version {
                require '[2.0, 3.2['
              }
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('p2')
    testProjectDir.newFile('p2/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api platform(project(':p1'))
        api platform(project(':p2'))
        api 'com.google.inject:guice'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          if (candidate.module == 'guice' && currentVersion == '2.0') {
            println "PROBE guice@\${candidate.version} bound=\${satisfiesDeclaredBound}"
          }
          !satisfiesDeclaredBound
        }
      """)

    when:
    def result = run()

    then: 'p2 pins the merged selection at 2.0, so 3.1 must fail even though p1 alone would admit it'
    result.output.contains('PROBE guice@3.1 bound=false')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a platform constraint that only rejects still bounds the module'() {
    given: 'one platform rejects 3.1 outright, a second admits a range that includes it'
    testProjectDir.newFile('settings.gradle') << "include 'p1', 'p2'\n"
    testProjectDir.newFolder('p1')
    testProjectDir.newFile('p1/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api('com.google.inject:guice') {
              version {
                reject '3.1'
              }
            }
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('p2')
    testProjectDir.newFile('p2/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api('com.google.inject:guice') {
              version {
                require '[2.0, 3.2['
              }
            }
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api platform(project(':p1'))
        api platform(project(':p2'))
        api 'com.google.inject:guice'
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'resolution stops at 3.0 because p1 rejects 3.1, and the rule must not offer it either'
    !report().outdated.dependencies.any { it.name == 'guice' && it.version == '3.0' }
  }

  // Gradle's own publisher writes `requires` beside `strictly`, so the strictly-only form only
  // arrives from a platform published by other tooling; the fixture hand-authors the metadata.
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a platform constraint stating only a strict version reaches the rule'() {
    given: 'a published platform whose module metadata states strictly with no requires'
    writeBuildFile(
      """
        api platform('com.example:strict-platform:1.0')
        api 'com.google.inject:guice'
      """,
      """
        rejectVersionIf {
          println "PROBE \${candidate.module} platformConstraints=" +
            "\${platformVersionConstraints.collect { it.strictVersion }}"
          return false
        }
      """)

    when:
    def result = run()

    then: 'the strictly-only edge is kept rather than dropped as stating nothing'
    result.output.contains('PROBE guice platformConstraints=[2.0]')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a platform constraint stating only a strict version still bounds the module'() {
    given: 'the platform pins guice purely with strictly, which resolution itself enforces'
    writeBuildFile(
      """
        api platform('com.example:strict-platform:1.0')
        api 'com.google.inject:guice'
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'the pinned module reports as current instead of being offered what Gradle refuses'
    report().current.dependencies.any { it.name == 'guice' && it.version == '2.0' }
    !report().outdated.dependencies*.name.contains('guice')
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a direct version beside a platform keeps its floor'() {
    given: 'the build states its own version for guice, so the platform does not bound it'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api platform(project(':platform'))
        api 'com.google.inject:guice:3.0'
      """,
      """
        checkConstraints = true
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'the declared floor is unaffected by the platform, every upgrade still shows'
    def guice = report().outdated.dependencies.find { it.name == 'guice' && it.version == '3.0' }
    guice != null
    guice.available.milestone == '3.1'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def 'a direct version in another configuration of the hierarchy keeps its floor'() {
    given: 'guice is versionless on one configuration and versioned on another that inherits it, ' +
      'so only the last declaration read would call the module versionless'
    testProjectDir.newFile('settings.gradle') << "include 'platform'\n"
    testProjectDir.newFolder('platform')
    testProjectDir.newFile('platform/build.gradle') <<
      """
        plugins { id 'java-platform' }
        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }
        dependencies {
          constraints {
            api 'com.google.inject:guice:2.0'
          }
        }
      """.stripIndent()
    writeBuildFile(
      """
        api platform(project(':platform'))
        api 'com.google.inject:guice'
        testImplementation 'com.google.inject:guice:3.0'
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: 'the version the build states is still offered its upgrade'
    def guice = report().outdated.dependencies.find { it.name == 'guice' && it.version == '3.0' }
    guice != null
    guice.available.milestone == '3.1'

    and: 'the versionless declaration stays bounded by the platform'
    report().outdated.dependencies.every { it.version != '2.0' }
    report().current.dependencies.any { it.name == 'guice' && it.version == '2.0' }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/402')
  def "a consumer's own constraint is not a platform bound"() {
    given: 'checkConstraints is off, so the declared map keeps the plain versionless declaration, ' +
      'and only the root constraints {} edge could wrongly read as a platform bound'
    writeBuildFile(
      """
        api 'com.google.inject:guice'
        constraints {
          api 'com.google.inject:guice:2.0'
        }
      """,
      """
        rejectVersionIf {
          !satisfiesDeclaredBound
        }
      """)

    when:
    run()

    then: "the root's own constraint edge is excluded, so the module stays unbound, not held to 2.0"
    report().outdated.dependencies*.name == ['guice']
    report().outdated.dependencies[0].available.milestone == '3.1'
  }
}
