package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Issue
import spock.lang.Specification

/**
 * With {@code rejectPreReleases}, on by default, a pre-release candidate is left out of the
 * report unless the current version is itself a pre-release, in which case a newer pre-release is
 * still reported. A convention the built-in markers do not cover is added to the check with
 * {@code preReleaseVersionIf}, so the property and its option govern it too.
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/440')
final class CheckPreReleaseVersionsSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private String reportFolder
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    classpathString = PluginClasspath.asFilesArgument()
    reportFolder = "${testProjectDir.root.path.replaceAll("\\\\", '/')}/build/dependencyUpdates"
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  private File writeBuildFile(String dependency, String taskConfig = '') {
    return writeDeclarations("implementation '$dependency'", taskConfig)
  }

  private File writeDeclarations(String declarations, String taskConfig) {
    def file = testProjectDir.newFile('build.gradle')
    file <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
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
          $declarations
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
          $taskConfig
        }
        """.stripIndent()
    return file
  }

  private File writeKotlinBuildFile(String declarations, String taskConfig) {
    def file = testProjectDir.newFile('build.gradle.kts')
    file <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        plugins {
          java
          id("io.github.ben-manes.versions")
        }

        repositories {
          maven(url = "${mavenRepoUrl}")
        }

        dependencies {
          $declarations
        }

        tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
          outputFormatter = "json"
          checkForGradleUpdate = false
          $taskConfig
        }
        """.stripIndent()
    return file
  }

  private Map runReport(List<String> extraArguments = []) {
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(['dependencyUpdates'] + extraArguments)
      .withPluginClasspath()
      .build()
    assert result.task(':dependencyUpdates').outcome == SUCCESS
    return new JsonSlurper().parseText(new File(reportFolder, 'report.json').text) as Map
  }

  def 'the newest stable and the newest pre-release are both reported'() {
    given:
    writeBuildFile('com.example:stepped-widget:1.0')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['stepped-widget']
    report.outdated.dependencies[0].available.milestone == '1.1'
    report.outdated.dependencies[0].available.preRelease == '1.2-beta'
  }

  def 'rejectPreReleases leaves the pre-release step out and keeps the stable one'() {
    given:
    writeBuildFile('com.example:stepped-widget:1.0', 'rejectPreReleases = true')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['stepped-widget']
    report.outdated.dependencies[0].available.milestone == '1.1'
    report.outdated.dependencies[0].available.preRelease == null
  }

  def 'the negative command line option restores the step a build leaves out'() {
    given: 'the build leaves the step out, which the option asks for back'
    writeBuildFile('com.example:stepped-widget:1.0', 'rejectPreReleases = true')

    when:
    def report = runReport(['--no-reject-pre-releases'])

    then:
    report.outdated.dependencies*.name == ['stepped-widget']
    report.outdated.dependencies[0].available.milestone == '1.1'
    report.outdated.dependencies[0].available.preRelease == '1.2-beta'
  }

  def 'the XML and JSON reports carry the step, and the HTML cell links both versions'() {
    given:
    writeBuildFile('com.example:stepped-widget:1.0', "outputFormatter = 'json,xml,html'")

    when:
    runUpdates()

    then: 'the XML element trails the revision level the resolution filled'
    def available = new XmlSlurper()
      .parse(new File(reportFolder, 'report.xml'))
      .outdated.dependencies.outdatedDependency[0].available
    available.milestone.text() == '1.1'
    available.preRelease.text() == '1.2-beta'
    available.release.isEmpty()
    available.integration.isEmpty()

    and: 'the JSON field trails them too'
    def json = new JsonSlurper()
      .parseText(new File(reportFolder, 'report.json').text).outdated.dependencies[0]
    json.available.milestone == '1.1'
    json.available.preRelease == '1.2-beta'

    and: 'each HTML version is linked on its own, so neither Sonatype url holds the pair'
    def html = new File(reportFolder, 'report.html').text
    html.contains('stepped-widget/1.1/bundle')
    html.contains('stepped-widget/1.2-beta/bundle')
    !html.contains('1.1 -&gt; 1.2-beta/bundle')
    !html.contains('1.1 -> 1.2-beta/bundle')
  }

  def 'under the release revision the step trails the release the resolution accepted'() {
    given:
    writeBuildFile('com.example:stepped-widget:1.0', "revision = 'release'")

    when:
    def report = runReport()

    then:
    report.outdated.dependencies[0].available.release == '1.1'
    report.outdated.dependencies[0].available.milestone == null
    report.outdated.dependencies[0].available.preRelease == '1.2-beta'
  }

  def 'the plain text row prints both steps'() {
    given:
    writeBuildFile('com.example:stepped-widget:1.0', "outputFormatter = 'text'")

    when:
    runUpdates()

    then:
    new File(reportFolder, 'report.txt').text
      .contains(' - com.example:stepped-widget [1.0 -> 1.1 -> 1.2-beta]')
  }

  def 'the plain text row skips a step that is not newer'() {
    given: 'the only candidate newer than the declared version is a pre-release'
    writeBuildFile('com.example:prerelease-widget:1.0', "outputFormatter = 'text'")

    when:
    runUpdates()

    then:
    new File(reportFolder, 'report.txt').text
      .contains(' - com.example:prerelease-widget [1.0 -> 1.2-beta]')
  }

  private void runUpdates(List<String> extraArguments = []) {
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments(['dependencyUpdates'] + extraArguments)
      .withPluginClasspath()
      .build()
    assert result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'a pre-release candidate is the step after the version in use by default'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0')

    when:
    def report = runReport()

    then: 'no release is newer, so only the pre-release step is reported'
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == null
    report.outdated.dependencies[0].available.preRelease == '1.2-beta'
  }

  def 'rejectPreReleases false is the default and spelling it out changes nothing'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0', 'rejectPreReleases = false')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.preRelease == '1.2-beta'
  }

  def 'the command line option leaves the step out for one run'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0')

    when:
    def report = runReport(['--reject-pre-releases'])

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies.isEmpty()
  }

  def 'the command line option overrides the property set to false in the build'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0', 'rejectPreReleases = false')

    when:
    def report = runReport(['--reject-pre-releases'])

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies.isEmpty()
  }

  def 'a module with only pre-releases published and no declared version to fall back to is unresolved'() {
    given: 'only 1.0-alpha and 1.0-beta of peer are published, and the declared 0.9 never was'
    writeBuildFile('com.example:prerelease-peer:0.9')

    when:
    def report = runReport()

    then: 'every candidate is rejected, so the dynamic query matches nothing'
    // A known cost of filtering by rejection, the same as with a rejectVersionIf that rejects
    // everything. The build still succeeds, and the rejected versions are listed in the reason.
    report.unresolved.dependencies*.name == ['prerelease-peer']
    report.unresolved.dependencies[0].reason.contains('1.0-beta')
    report.outdated.dependencies.isEmpty()
  }

  def 'a newer pre-release is still reported to a build already on one'() {
    given:
    writeBuildFile('com.example:prerelease-peer:1.0-alpha')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies[0].available.milestone == '1.0-beta'
  }

  def 'a rejectVersionIf filter covers a convention the built-in markers do not'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:1.0', '''
          rejectVersionIf {
            candidate.version.endsWith('-flagged') && !currentVersion.endsWith('-flagged')
          }
        ''')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies.isEmpty()
  }

  def 'the built-in filter and a rejectVersionIf filter both apply'() {
    given: 'the filter rejects guava 16.0, leaving only the 16.0-rc1 the built-in check rejects'
    writeBuildFile('com.google.guava:guava:15.0', '''
          rejectVersionIf {
            candidate.version == '16.0'
          }
        ''')

    when:
    def report = runReport()

    then: 'the rule rejects 16.0 outright, and the built-in check reports 16.0-rc1 as the step'
    report.outdated.dependencies*.name == ['guava']
    report.outdated.dependencies[0].available.milestone == null
    report.outdated.dependencies[0].available.preRelease == '16.0-rc1'
  }

  def 'under the integration revision a snapshot is reported'() {
    given:
    writeBuildFile('com.example:snapshot-mixed:1.5', "revision = 'integration'")

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['snapshot-mixed']
    report.outdated.dependencies[0].available.integration == null
    report.outdated.dependencies[0].available.preRelease == '2.0-SNAPSHOT'
  }

  def 'the property reads false by default, and the pre-release is the second step'() {
    given: 'a beta rather than a snapshot, so the revision filter leaves the candidate alone'
    writeBuildFile('com.example:prerelease-widget:1.0', '''
          revision = 'integration'
          doLast { println "rejectPreReleases=$rejectPreReleases" }
        ''')

    when:
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def report = new JsonSlurper().parseText(new File(reportFolder, 'report.json').text) as Map

    then:
    result.task(':dependencyUpdates').outcome == SUCCESS
    result.output.contains('rejectPreReleases=false')
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.integration == null
    report.outdated.dependencies[0].available.preRelease == '1.2-beta'
  }

  def 'under the integration revision an explicit setting still applies'() {
    given:
    writeBuildFile('com.example:prerelease-widget:1.0', '''
          revision = 'integration'
          rejectPreReleases = true
        ''')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies.isEmpty()
  }

  private void writeMultiProjectBuild(String dependency, String taskConfig, boolean applyInSubprojects = false) {
    // A subproject applying the plugin registers settings of its own, all unset, which is what makes
    // inheriting from the root distinguishable from having no entry at all.
    def subprojectPlugin = applyInSubprojects
      ? '''
          apply plugin: 'io.github.ben-manes.versions'
          tasks.named('dependencyUpdates').configure {
            checkForGradleUpdate = false
          }
        '''
      : ''
    testProjectDir.newFile('settings.gradle') << "include 'app'"
    testProjectDir.newFile('build.gradle') <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'io.github.ben-manes.versions'

        subprojects {
          apply plugin: 'java'
          $subprojectPlugin

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }
        }

        tasks.named('dependencyUpdates').configure {
          outputFormatter = 'json'
          checkForGradleUpdate = false
          $taskConfig
        }
        """.stripIndent()
    testProjectDir.newFolder('app')
    testProjectDir.newFile('app/build.gradle') <<
      """
        dependencies {
          implementation '$dependency'
        }
        """.stripIndent()
  }

  def 'a subproject inherits rejectPreReleases from the root task'() {
    given: 'the root turns the step off, which the default would leave on'
    writeMultiProjectBuild('com.example:prerelease-widget:1.0', 'rejectPreReleases = true')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies.isEmpty()
  }

  def 'a Groovy rule reading isPreRelease leaves out what the property would, and no more'() {
    given: 'the property is off, so the rule is the only thing that can reject'
    writeDeclarations(
      '''
          implementation 'com.example:prerelease-widget:1.0'
          implementation 'com.example:prerelease-peer:1.0-alpha'
        ''',
      '''
          rejectPreReleases = false
          rejectVersionIf {
            isPreRelease(candidate.version) && !isPreRelease(currentVersion)
          }
        ''')

    when:
    def report = runReport()

    then: 'the widget loses its pre-release, and the peer, already on one, is still shown its upgrade'
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies[0].available.milestone == '1.0-beta'
  }

  def 'a Kotlin rule reading isPreRelease leaves out what the property would, and no more'() {
    given:
    writeKotlinBuildFile(
      '''
          implementation("com.example:prerelease-widget:1.0")
          implementation("com.example:prerelease-peer:1.0-alpha")
        ''',
      '''
          rejectPreReleases = false
          rejectVersionIf {
            isPreRelease(candidate.version) && !isPreRelease(currentVersion)
          }
        ''')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies[0].available.milestone == '1.0-beta'
  }

  def 'a rule reading isPreRelease is applied under the command line option too'() {
    given: 'the property is off and the rule has no pre-release exemption for the current version, so only the rule hides the beta'
    writeBuildFile('com.example:prerelease-peer:1.0-alpha', '''
          rejectPreReleases = false
          rejectVersionIf {
            isPreRelease(candidate.version) && candidate.version != currentVersion
          }
        ''')

    when: 'the option turns off a built-in check the build already turned off'
    def report = runReport(['--no-reject-pre-releases'])

    then: 'the rule is the build\'s own, and still hides the beta'
    report.current.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies.isEmpty()
  }

  def 'the positive command line option leaves a rule reading isPreRelease alone'() {
    given: 'the built-in check exempts a build already on a pre-release, so the rule is what hides the beta'
    writeBuildFile('com.example:prerelease-peer:1.0-alpha', '''
          rejectPreReleases = false
          rejectVersionIf {
            isPreRelease(candidate.version) && candidate.version != currentVersion
          }
        ''')

    when:
    def report = runReport(['--reject-pre-releases'])

    then:
    report.current.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies.isEmpty()
  }

  def 'a Groovy rule reading isPreRelease with no argument leaves out what the property would, and no more'() {
    given: 'the property is off, so the rule is the only thing that can reject'
    writeDeclarations(
      '''
          implementation 'com.example:prerelease-widget:1.0'
          implementation 'com.example:prerelease-peer:1.0-alpha'
        ''',
      '''
          rejectPreReleases = false
          rejectVersionIf {
            isPreRelease()
          }
        ''')

    when:
    def report = runReport()

    then: 'the widget loses its pre-release, and the peer, already on one, is still shown its upgrade'
    report.current.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies*.name == ['prerelease-peer']
    report.outdated.dependencies[0].available.milestone == '1.0-beta'
  }

  def 'a Kotlin build exempting one module from both checks keeps the checks on for the rest, and both options lift the rest'() {
    given: 'the exempted module has a newer version that is both a pre-release and out of bound'
    writeKotlinBuildFile(
      '''
          implementation("com.example:prerelease-widget") {
            version {
              require("1.0")
              reject("1.2-beta")
            }
          }
          implementation("com.example:prerelease-flagged:1.0")
          implementation("com.google.guava:guava") {
            version {
              require("15.0")
              reject("[16.0,)")
            }
          }
        ''',
      '''
          preReleaseVersionIf { it.endsWith("-flagged") }
          exemptFromBuiltInChecksIf { candidate.module == "prerelease-widget" }
        ''')

    when:
    def report = runReport()

    then: 'the exemption reaches the version the widget both bounds out and marks pre-release'
    def widget = report.outdated.dependencies.find { it.name == 'prerelease-widget' }
    widget.available.milestone == '1.2-beta'
    widget.available.preRelease == null

    and: 'the added convention holds the flagged module back to a second step'
    report.outdated.dependencies.find { it.name == 'prerelease-flagged' }.available.preRelease == '3.0-flagged'

    and: 'the bound check holds guava, which only the pre-release below the bound gets past'
    report.outdated.dependencies.find { it.name == 'guava' }.available.preRelease == '16.0-rc1'

    when: 'the option asks for what the bound check leaves out'
    def unfiltered = runReport(['--no-reject-out-of-bounds'])

    then: 'the bound check is off for that run, and the exemption has nothing left to exempt there'
    unfiltered.outdated.dependencies*.name.sort() == ['guava', 'prerelease-flagged', 'prerelease-widget']
    unfiltered.outdated.dependencies.find { it.name == 'guava' }.available.milestone == '16.0'
    unfiltered.outdated.dependencies.find { it.name == 'prerelease-flagged' }.available.preRelease == '3.0-flagged'
  }

  def 'a Groovy build exempting one module from both checks keeps the checks on for the rest'() {
    given: 'the closure reads candidate bare, as a rejectVersionIf closure does'
    writeDeclarations(
      '''
          implementation('com.example:prerelease-widget') {
            version {
              require '1.0'
              reject '1.2-beta'
            }
          }
          implementation 'com.example:prerelease-flagged:1.0'
          implementation('com.google.guava:guava') {
            version {
              require '15.0'
              reject '[16.0,)'
            }
          }
        ''',
      '''
          preReleaseVersionIf { it.endsWith('-flagged') }
          exemptFromBuiltInChecksIf { candidate.module == 'prerelease-widget' }
        ''')

    when:
    def report = runReport()

    then:
    def widget = report.outdated.dependencies.find { it.name == 'prerelease-widget' }
    widget.available.milestone == '1.2-beta'
    widget.available.preRelease == null
    report.outdated.dependencies.find { it.name == 'prerelease-flagged' }.available.preRelease == '3.0-flagged'
    report.outdated.dependencies.find { it.name == 'guava' }.available.preRelease == '16.0-rc1'
  }

  def 'an exemption reading a negated member is narrowed to the other check'() {
    given: 'the widget is bounded out of its pre-release, and the exemption keeps the bound check'
    writeDeclarations(
      '''
          implementation('com.example:prerelease-widget') {
            version {
              require '1.0'
              reject '1.2-beta'
            }
          }
          implementation 'com.example:prerelease-flagged:1.0'
        ''',
      '''
          preReleaseVersionIf { it.endsWith('-flagged') }
          exemptFromBuiltInChecksIf { !isOutOfDeclaredBounds() }
        ''')

    when:
    def report = runReport()

    then: 'the flagged module is shown its pre-release, and the bounded widget is not'
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.milestone == '3.0-flagged'
    report.current.dependencies*.name == ['prerelease-widget']
  }

  def 'the positive command line option applies the check with the exemption inside it'() {
    given: 'the property is off in the build, and the exemption covers the widget alone'
    writeDeclarations(
      '''
          implementation 'com.example:prerelease-widget:1.0'
          implementation 'com.example:prerelease-flagged:1.0'
        ''',
      '''
          rejectPreReleases = false
          preReleaseVersionIf { it.endsWith('-flagged') }
          exemptFromBuiltInChecksIf { candidate.module == 'prerelease-widget' }
        ''')

    when:
    def report = runReport(['--reject-pre-releases'])

    then: 'the option turns the check on for the flagged module, and the widget stays exempt'
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
    report.current.dependencies*.name == ['prerelease-flagged']
  }

  def 'a subproject inherits exemptFromBuiltInChecksIf from the root task'() {
    given: 'the subproject applies the plugin, so it has settings of its own to inherit through'
    writeMultiProjectBuild('com.example:prerelease-widget:1.0', "exemptFromBuiltInChecksIf { candidate.module == 'prerelease-widget' }", true)

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
  }

  def 'an exemption declared on a subproject stands in the aggregated report'() {
    given: 'the subproject exempts the module, with nothing configured on the root task'
    writeMultiProjectBuild('com.example:prerelease-widget:1.0', '', true)
    new File(testProjectDir.root, 'app/build.gradle') <<
      """
        tasks.named('dependencyUpdates').configure {
          exemptFromBuiltInChecksIf { candidate.module == 'prerelease-widget' }
        }
      """.stripIndent()

    when:
    def report = runReport()

    then: 'the aggregated report shows the row the subproject resolved, exemption included'
    report.outdated.dependencies*.name == ['prerelease-widget']
    report.outdated.dependencies[0].available.milestone == '1.2-beta'
  }

  def 'exemptions added in two calls both apply'() {
    given: 'the second call exempts the bounded guava'
    writeDeclarations(
      '''
          implementation 'com.example:prerelease-widget:1.0'
          implementation('com.google.guava:guava') {
            version {
              require '15.0'
              reject '[16.0,)'
            }
          }
        ''',
      '''
          exemptFromBuiltInChecksIf { candidate.module == 'prerelease-widget' }
          exemptFromBuiltInChecksIf { candidate.module == 'guava' }
        ''')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name.sort() == ['guava', 'prerelease-widget']
    report.outdated.dependencies.find { it.name == 'guava' }.available.milestone == '16.0'
    report.outdated.dependencies.find { it.name == 'prerelease-widget' }.available.milestone == '1.2-beta'
  }

  def 'under the integration revision the added convention marks the second step'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:1.0', '''
          revision = 'integration'
          preReleaseVersionIf { it.endsWith('-flagged') }
        ''')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.integration == null
    report.outdated.dependencies[0].available.preRelease == '3.0-flagged'
  }

  def 'a qualifier not in the built-in markers is passed through'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:1.0')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.milestone == '3.0-flagged'
  }

  def 'a build already on a version the added convention matches is still shown a newer one'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:2.0-flagged', "preReleaseVersionIf { it.endsWith('-flagged') }")

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.milestone == '3.0-flagged'
  }

  def 'the command line option leaves out a step the added convention marked'() {
    given:
    writeBuildFile('com.example:prerelease-flagged:1.0', "preReleaseVersionIf { it.endsWith('-flagged') }")

    when:
    def report = runReport(['--reject-pre-releases'])

    then:
    report.current.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies.isEmpty()
  }

  def 'a rule reading isPreRelease answers for the added convention'() {
    given: 'the property is off, so the rule is the only thing that can reject'
    writeBuildFile('com.example:prerelease-flagged:1.0', '''
          rejectPreReleases = false
          preReleaseVersionIf { it.endsWith('-flagged') }
          rejectVersionIf {
            isPreRelease(candidate.version) && !isPreRelease(currentVersion)
          }
        ''')

    when:
    def report = runReport()

    then:
    report.current.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies.isEmpty()
  }

  def 'a subproject inherits preReleaseVersionIf from the root task'() {
    given: 'the subproject applies the plugin, so it has settings of its own to inherit through'
    writeMultiProjectBuild('com.example:prerelease-flagged:1.0', "preReleaseVersionIf { it.endsWith('-flagged') }", true)

    when:
    def report = runReport()

    then: 'the convention reached the subproject, so the flagged candidate is the second step'
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.milestone == null
    report.outdated.dependencies[0].available.preRelease == '3.0-flagged'
  }

  def 'preReleaseVersionIf compiles and applies under the Kotlin DSL'() {
    given:
    writeKotlinBuildFile(
      'implementation("com.example:prerelease-flagged:1.0")',
      'preReleaseVersionIf { it.endsWith("-flagged") }')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name == ['prerelease-flagged']
    report.outdated.dependencies[0].available.preRelease == '3.0-flagged'
  }

  def 'conventions added in two calls both apply'() {
    given: 'the second call matches the guava release the built-in markers pass through'
    writeDeclarations(
      '''
          implementation 'com.example:prerelease-flagged:1.0'
          implementation 'com.google.guava:guava:15.0'
        ''',
      '''
          preReleaseVersionIf { it.endsWith('-flagged') }
          preReleaseVersionIf { it == '16.0' }
        ''')

    when:
    def report = runReport()

    then:
    report.outdated.dependencies*.name.sort() == ['guava', 'prerelease-flagged']
    report.outdated.dependencies.find { it.name == 'guava' }.available.preRelease == '16.0'
    report.outdated.dependencies.find { it.name == 'prerelease-flagged' }.available.preRelease == '3.0-flagged'
  }
}
