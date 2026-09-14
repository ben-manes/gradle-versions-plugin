package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.ReportRules
import com.github.benmanes.gradle.versions.updates.VersionMapping
import com.github.benmanes.gradle.versions.updates.VersionTiers
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A row reports the latest version that shares the major and minor parts of the version in use,
 * then the latest that shares its major part, ahead of the latest version overall.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/69')
final class VersionTierStepsSpec extends Specification {
  private static final def LOGGER = Logging.getLogger(VersionTierStepsSpec)

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String mavenRepoUrl

  def 'setup'() {
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private static PartialStatus statusOf(String declaredVersion, String latestVersion,
    String patchVersion, String minorVersion) {
    return new PartialStatus('com.example', 'widget', declaredVersion, null, latestVersion, null, null,
      false, [], ':', [], [], false, null, [], false, null, false, patchVersion, minorVersion)
  }

  private static List<String> candidatesOf(List<String> versions) {
    return versions.collect { "com.example:widget:$it".toString() }
  }

  private static Action<ResolutionStrategyWithCurrent> rejecting(String rejectedVersion) {
    return { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == rejectedVersion) {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>
  }

  private void writeBuild(String declaration = "implementation 'com.example:tiered-widget:1.0.1'", String extra = '') {
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files(${PluginClasspath.asFilesArgument()})
          }
        }

        apply plugin: 'java'
        apply plugin: 'io.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          $declaration
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'text,json,xml,html'
          checkForGradleUpdate = false
        }
        $extra
        """.stripIndent()
  }

  private String runReport() {
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    assert result.task(':dependencyUpdates').outcome == SUCCESS
    return new File(testProjectDir.root, 'build/dependencyUpdates/report.txt').text
  }

  @Unroll
  def 'Queries the versions of #version sharing #parts parts with #selector'() {
    expect:
    VersionTiers.INSTANCE.selector(version, parts) == selector

    where:
    version         | parts || selector
    '1.10.18'       | 2     || '1.10+'
    '1.10.18'       | 1     || '1+'
    '33.7.1-jre'    | 2     || '33.7+'
    '31.0-jre'      | 2     || '31.0+'
    '1.0.0.RELEASE' | 2     || '1.0+'
    '2021.0.1'      | 2     || '2021.0+'
    '1_2_3'         | 2     || '1_2+'
    '5'             | 2     || null
    '5'             | 1     || '5+'
    '5-jre'         | 1     || '5+'
    'r09'           | 1     || null
    'none'          | 1     || null
    '1.+'           | 1     || null
    '1.0.+'         | 2     || null
    '[1.0,2.0)'     | 2     || null
    'latest.release' | 1    || null
  }

  @Unroll
  def 'Reads #candidate as sharing #parts parts with #version: #shared'() {
    expect:
    VersionTiers.INSTANCE.shares(candidate, version, parts) == shared

    where:
    candidate       | version       | parts || shared
    '1.10.19'       | '1.10.18'     | 2     || true
    '1.11.0'        | '1.10.18'     | 2     || false
    '1.11.0'        | '1.10.18'     | 1     || true
    '1.1.0'         | '1.10.18'     | 2     || false
    '33.7.2-jre'    | '33.7.1-jre'  | 2     || true
    '1.01.3'        | '1.1.2'       | 2     || true
    '2.0'           | '5'           | 1     || false
    'r10'           | 'r09'         | 1     || false
  }

  def 'The report leaves out a merged step that its own rules reject'() {
    given:
    def status = statusOf('1.0.1', '2.1.0', '1.0.2', '1.1.1')
    def candidates = [':': candidatesOf(['1.0.1', '1.0.2', '1.1.1', '2.1.0'])]

    when:
    def applied = new ReportRules(rejecting('1.1.1'), LOGGER, 'milestone', false, null, null)
      .applyTo([status], candidates)

    then:
    applied[0].patchVersion == '1.0.2'
    applied[0].minorVersion == null
  }

  def 'The report leaves out a merged step later than the version its rules moved the row to'() {
    given:
    def status = statusOf('1.0.1', '1.1.1', '1.0.2', '1.1.1')
    def candidates = [':': candidatesOf(['1.0.1', '1.0.2', '1.1.1'])]

    when:
    def applied = new ReportRules(rejecting('1.1.1'), LOGGER, 'milestone', false, null, null)
      .applyTo([status], candidates)

    then:
    applied[0].latestVersion == '1.0.2'
    applied[0].patchVersion == '1.0.2'
    applied[0].minorVersion == null
  }

  def 'Each report format prints the latest patch and minor versions before the latest version'() {
    given:
    writeBuild()

    when:
    def text = runReport()

    then:
    text.contains(' - com.example:tiered-widget [1.0.1 -> 1.0.2 -> 1.1.1 -> 2.1.0 -> 2.2.0-rc1]')

    and:
    def json = new JsonSlurper().parseText(new File(testProjectDir.root, 'build/dependencyUpdates/report.json').text)
    def available = json.outdated.dependencies[0].available
    available.patch == '1.0.2'
    available.minor == '1.1.1'
    available.milestone == '2.1.0'
    available.preRelease == '2.2.0-rc1'

    and:
    def xml = new XmlSlurper().parse(new File(testProjectDir.root, 'build/dependencyUpdates/report.xml'))
      .outdated.dependencies.outdatedDependency[0].available
    xml.patch.text() == '1.0.2'
    xml.minor.text() == '1.1.1'

    and:
    def html = new File(testProjectDir.root, 'build/dependencyUpdates/report.html').text
    (html =~ /(?s)1\.0\.2.*1\.1\.1.*2\.1\.0.*2\.2\.0-rc1/).find()
  }

  def 'Prints no step for a tier whose only later version is a pre-release'() {
    given: 'the only 1.1.x after 1.1.1 is 1.1.2-beta, so the tier is queried and nothing in it is accepted'
    writeBuild("implementation 'com.example:tiered-widget:1.1.1'")

    expect:
    runReport().contains(' - com.example:tiered-widget [1.1.1 -> 2.1.0 -> 2.2.0-rc1]')
  }

  def 'Prints the row when a rule throws on a version only a tier query reaches'() {
    given: 'the latest resolution stops at 2.1.0, so 1.0.2 is reached by the patch query alone'
    writeBuild("implementation 'com.example:tiered-widget:1.0.1'",
      """
        configurations.configureEach {
          resolutionStrategy.componentSelection.all { selection ->
            if (selection.candidate.version == '1.0.2') {
              throw new GradleException('thrown by the build')
            }
          }
        }
      """)

    expect:
    runReport().contains(' - com.example:tiered-widget [1.0.1 -> 1.1.1 -> 2.1.0 -> 2.2.0-rc1]')
  }

  def 'Prints the steps of the module a substitution rule resolves in place of the declared one'() {
    given:
    writeBuild("implementation 'com.example:old-widget:1.0.0'",
      """
        configurations.configureEach {
          resolutionStrategy.dependencySubstitution {
            substitute module('com.example:old-widget') using module('com.example:tiered-widget:1.0.1')
          }
        }
      """)

    expect:
    runReport().contains(' - com.example:tiered-widget [1.0.1 -> 1.0.2 -> 1.1.1 -> 2.1.0 -> 2.2.0-rc1]')
  }

  def 'Prints no step for a dynamic declared version, which already selects its tier'() {
    given:
    writeBuild("constraints { implementation 'com.example:tiered-widget:1.+' }",
      "tasks.named('dependencyUpdates').configure { checkConstraints = true }")

    expect:
    runReport().contains(' - com.example:tiered-widget [1.+ -> 2.1.0 -> 2.2.0-rc1]')
  }

  def 'A status that found no version in a tier does not veto the version another found'() {
    given: 'a partial written by an older release reads with no tier versions'
    def found = statusOf('1.0.1', '2.1.0', '1.0.2', '1.1.1')
    def none = statusOf('1.0.1', '2.1.0', null, null)

    when:
    def versions = new VersionMapping(LOGGER, [found, none])

    then:
    versions.patchByCurrent.values() as List == ['1.0.2']
    versions.minorByCurrent.values() as List == ['1.1.1']
  }

  def 'Statuses that found different versions in a tier leave it out'() {
    when:
    def versions = new VersionMapping(LOGGER,
      [statusOf('1.0.1', '2.1.0', '1.0.2', '1.1.1'), statusOf('1.0.1', '2.1.0', '1.0.2', '1.1.0')])

    then:
    versions.patchByCurrent.values() as List == ['1.0.2']
    versions.minorByCurrent.isEmpty()
  }

  def 'Leaves out a version that rejectVersionIf rejects'() {
    given:
    writeBuild("implementation 'com.example:tiered-widget:1.0.1'",
      "tasks.named('dependencyUpdates').configure { rejectVersionIf { it.candidate.version == '1.1.1' } }")

    expect:
    runReport().contains(' - com.example:tiered-widget [1.0.1 -> 1.0.2 -> 1.1.0 -> 2.1.0 -> 2.2.0-rc1]')
  }

  def 'Leaves out a version that a rule on the configuration rejects'() {
    given:
    writeBuild("implementation 'com.example:tiered-widget:1.0.1'",
      """
        configurations.configureEach {
          resolutionStrategy.componentSelection.all { selection ->
            if (selection.candidate.version in ['1.0.2', '1.1.1', '2.1.0']) {
              selection.reject('rejected by the build')
            }
          }
        }
      """)

    expect:
    runReport().contains(' - com.example:tiered-widget [1.0.1 -> 1.1.0 -> 2.0.0 -> 2.2.0-rc1]')
  }

  def 'Leaves out a version that a rule rejects by its metadata'() {
    given:
    writeBuild("implementation 'com.example:tiered-widget:1.0.1'",
      """
        dependencies {
          components {
            withModule('com.example:tiered-widget') { details ->
              if (details.id.version in ['1.0.2', '1.1.1', '2.1.0']) {
                details.status = 'milestone'
              }
            }
          }
        }

        tasks.named('dependencyUpdates').configure {
          resolutionStrategy {
            componentSelection {
              all { selection ->
                if (selection.metadata?.status == 'milestone') {
                  selection.reject('milestone status')
                }
              }
            }
          }
        }
      """)

    expect:
    runReport().contains(' - com.example:tiered-widget [1.0.1 -> 1.1.0 -> 2.0.0 -> 2.2.0-rc1]')
  }

  def 'Leaves out a version that the declaration rejects'() {
    given:
    writeBuild("""
      implementation('com.example:tiered-widget') {
        version {
          require '1.0.1'
          reject '1.1.1'
        }
      }
    """)

    expect:
    runReport().contains(' - com.example:tiered-widget [1.0.1 -> 1.0.2 -> 1.1.0 -> 2.1.0 -> 2.2.0-rc1]')
  }

  def "A row shared by several projects offers no version one project's rules reject"() {
    given: 'a root that accepts every version and a subproject that rejects 1.0.2 and 1.1.1'
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files(${PluginClasspath.asFilesArgument()})
          }
        }

        allprojects {
          apply plugin: 'java'
          apply plugin: 'io.github.ben-manes.versions'

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          dependencies {
            implementation 'com.example:tiered-widget:1.0.0'
          }

          tasks.named('dependencyUpdates').configure {
            checkForGradleUpdate = false
          }
        }
      """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        tasks.named('dependencyUpdates').configure {
          rejectVersionIf { it.candidate.version in ['1.0.2', '1.1.1'] }
        }
      """.stripIndent()

    when:
    def text = runReport()

    then:
    text.contains(' - com.example:tiered-widget [1.0.0 -> 2.1.0 -> 2.2.0-rc1]')
  }

  def 'Prints the latest patch and minor versions of a Kotlin-group module that a classpath inherits'() {
    given: 'compileClasspath inherits the declaration, so the query copy also holds it at its declared version'
    writeBuild("implementation 'org.jetbrains.kotlinx:tiered-kx:1.0.1'")

    expect:
    runReport().contains(' - org.jetbrains.kotlinx:tiered-kx [1.0.1 -> 1.0.2 -> 1.1.1 -> 2.1.0]')
  }

  def 'Prints the latest patch of a version whose patch adds a numeric part ahead of its suffix'() {
    given: 'the patch of 31.0-jre is 31.0.1-jre, which the prefix 31.0- does not match'
    writeBuild("implementation 'com.example:flavored-widget:31.0-jre'")

    expect:
    runReport().contains(' - com.example:flavored-widget [31.0-jre -> 31.0.1-jre -> 31.1-jre -> 32.0.0-jre]')
  }
}
