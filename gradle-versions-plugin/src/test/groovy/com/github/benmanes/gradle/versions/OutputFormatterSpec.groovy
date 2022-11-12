package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

final class OutputFormatterSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath
  private String reportFolder
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.getResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
    classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    reportFolder = "${testProjectDir.root.path.replaceAll("\\\\", '/')}/build/dependencyUpdates"
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  def 'Does not show updates for dependencies when outputFormatter is a closure'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          outputFormatter = {}
          checkForGradleUpdate = false // future proof tests from breaking
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    !result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'Build fails when unsupported outputFormatter is provided'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          outputFormatter = 13
          checkForGradleUpdate = false // future proof tests from breaking
        }
        """.stripIndent()

    when:
    def runner = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')

    then:
    runner.buildAndFail()
  }

  def 'outputFormatter defaults to text output'() {
    given:
    def reportFile = new File(reportFolder, "report.txt")
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates {
          checkForGradleUpdate = false // future proof tests from breaking
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()

    then:
    reportFile.exists()
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'outputFormatter plain - outputs text output'() {
    given:
    def reportFile = new File(reportFolder, "report.txt")
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
          implementation 'com.thoughtworks.xstream:xstream'
        }

        dependencyUpdates {
          outputFormatter = 'plain'
          checkForGradleUpdate = false // future proof tests from breaking
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def expected =
      """
    ------------------------------------------------------------
    : Project Dependency Updates (report to plain text file)
    ------------------------------------------------------------

    The following dependencies have later milestone versions:
     - com.google.inject:guice [2.0 -> 3.1]
         https://code.google.com/p/google-guice/

    Failed to compare versions for the following dependencies because they were declared without version:
     - com.thoughtworks.xstream:xstream
      """.stripIndent().replace('\r', '').replace('\n', System.lineSeparator())
    def actual = reportFile.text

    then:
    reportFile.exists()
    expected == actual
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'outputFormatter json - outputs json output'() {
    given:
    def jsonSlurper = new JsonSlurper()
    def reportFile = new File(reportFolder, 'report.json')
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
          implementation 'com.thoughtworks.xstream:xstream'
        }

        dependencyUpdates {
          outputFormatter = 'json'
          checkForGradleUpdate = false // future proof tests from breaking
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def expected = jsonSlurper.parseText(
      """
    {
        "current": {
            "dependencies": [

            ],
            "count": 0
        },
        "gradle": {
            "current": {
                "version": "",
                "reason": "update check disabled",
                "isUpdateAvailable": false,
                "isFailure": false
            },
            "nightly": {
                "version": "",
                "reason": "update check disabled",
                "isUpdateAvailable": false,
                "isFailure": false
            },
            "enabled": false,
            "releaseCandidate": {
                "version": "",
                "reason": "update check disabled",
                "isUpdateAvailable": false,
                "isFailure": false
            },
            "running": {
                "version": "",
                "reason": "update check disabled",
                "isUpdateAvailable": false,
                "isFailure": false
            }
        },
        "exceeded": {
            "dependencies": [

            ],
            "count": 0
        },
        "outdated": {
            "dependencies": [
                {
                    "group": "com.google.inject",
                    "available": {
                        "release": null,
                        "milestone": "3.1",
                        "integration": null
                    },
                    "userReason": null,
                    "version": "2.0",
                    "projectUrl": "https://code.google.com/p/google-guice/",
                    "name": "guice"
                }
            ],
            "count": 1
        },
        "unresolved": {
            "dependencies": [

            ],
            "count": 0
        },
        "undeclared": {
            "dependencies": [
                {
                    "group": "com.thoughtworks.xstream",
                    "name": "xstream",
                    "version": null,
                    "projectUrl": null,
                    "userReason": null
                }
            ],
            "count": 1
        },
        "count": 2
    }
      """.stripIndent())
    def actual = jsonSlurper.parseText(reportFile.text)

    then:
    reportFile.exists()
    expected == actual
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'outputFormatter xml - outputs xml output'() {
    given:
    def xmlParser = new XmlParser()
    def reportFile = new File(reportFolder, 'report.xml')
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
          implementation 'com.thoughtworks.xstream:xstream'
        }

        dependencyUpdates {
          outputFormatter = 'xml'
          checkForGradleUpdate = false // future proof tests from breaking
        }
      """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def expected = xmlParser.parseText(
      """    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <response>
      <count>2</count>
      <current>
        <count>0</count>
        <dependencies/>
      </current>
      <outdated>
        <count>1</count>
        <dependencies>
          <outdatedDependency>
            <group>com.google.inject</group>
            <name>guice</name>
            <version>2.0</version>
            <projectUrl>https://code.google.com/p/google-guice/</projectUrl>
            <available>
              <milestone>3.1</milestone>
            </available>
          </outdatedDependency>
        </dependencies>
      </outdated>
      <exceeded>
        <count>0</count>
        <dependencies/>
      </exceeded>
      <undeclared>
        <count>1</count>
        <dependencies>
          <dependency>
            <group>com.thoughtworks.xstream</group>
            <name>xstream</name>
          </dependency>
        </dependencies>
      </undeclared>
      <unresolved>
        <count>0</count>
        <dependencies/>
      </unresolved>
      <gradle>
        <enabled>false</enabled>
        <running>
          <version></version>
          <isUpdateAvailable>false</isUpdateAvailable>
          <isFailure>false</isFailure>
          <reason>update check disabled</reason>
        </running>
        <current>
          <version></version>
          <isUpdateAvailable>false</isUpdateAvailable>
          <isFailure>false</isFailure>
          <reason>update check disabled</reason>
        </current>
        <releaseCandidate>
          <version></version>
          <isUpdateAvailable>false</isUpdateAvailable>
          <isFailure>false</isFailure>
          <reason>update check disabled</reason>
        </releaseCandidate>
        <nightly>
          <version></version>
          <isUpdateAvailable>false</isUpdateAvailable>
          <isFailure>false</isFailure>
          <reason>update check disabled</reason>
        </nightly>
      </gradle>
    </response>
      """.stripIndent()).toString()
    def actual = xmlParser.parseText(reportFile.text).toString()

    then:
    reportFile.exists()
    expected == actual
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'outputFormatter plain - outputs text output with user reasons'() {
    given:
    def reportFile = new File(reportFolder, "report.txt")
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation('backport-util-concurrent:backport-util-concurrent:3.1') { because 'I said so' }
          implementation('backport-util-concurrent:backport-util-concurrent-java12:3.1')
          implementation('com.google.guava:guava:99.0-SNAPSHOT') { because 'I know the future' }
          implementation('com.google.guava:guava-tests:99.0-SNAPSHOT')
          implementation('com.google.inject:guice:2.0') { because 'That\\'s just the way it is' }
          implementation('com.google.inject.extensions:guice-multibindings:2.0')
          implementation('com.github.ben-manes:unresolvable:1.0') { because 'Life is hard' }
          implementation('com.github.ben-manes:unresolvable2:1.0')
        }

        dependencyUpdates {
          outputFormatter = 'plain'
          checkForGradleUpdate = false // future proof tests from breaking
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def expected = """
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
 - com.google.inject:guice [2.0 -> 3.1]
     That's just the way it is
     https://code.google.com/p/google-guice/
 - com.google.inject.extensions:guice-multibindings [2.0 -> 3.0]
     https://code.google.com/p/google-guice/

Failed to determine the latest version for the following dependencies (use --info for details):
 - com.github.ben-manes:unresolvable
     Life is hard
 - com.github.ben-manes:unresolvable2
""".replace('\r', '').replace('\n', System.lineSeparator())
    def actual = reportFile.text

    then:
    reportFile.exists()
    expected == actual
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'outputFormatter custom - modify unresolvable dependencies then outputs text output'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        import com.github.benmanes.gradle.versions.reporter.PlainTextReporter

        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: 'com.github.ben-manes.versions'

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation('backport-util-concurrent:backport-util-concurrent:3.1') { because 'I said so' }
          implementation('backport-util-concurrent:backport-util-concurrent-java12:3.1')
          implementation('com.google.guava:guava:99.0-SNAPSHOT') { because 'I know the future' }
          implementation('com.google.guava:guava-tests:99.0-SNAPSHOT')
          implementation('com.google.inject:guice:2.0') { because 'That\\'s just the way it is' }
          implementation('com.google.inject.extensions:guice-multibindings:2.0')
          implementation('com.github.ben-manes:unresolvable:1.0') { because 'Life is hard' }
          implementation('com.github.ben-manes:unresolvable2:1.0')
          implementation('com.github.ben-manes:unresolvable20:1.0')
          implementation('com.github.ben-manes:unresolvable21:1.0')
          implementation('com.github.ben-manes:unresolvable3:1.0')
        }

        dependencyUpdates {
          outputFormatter = { result ->
            result.unresolved.dependencies.removeIf {
              it.name == 'unresolvable20' || it.name == 'unresolvable21'
            }
            def plainTextReporter = new PlainTextReporter(project, revision, gradleReleaseChannel)
            plainTextReporter.write(System.out, result)
          }
          checkForGradleUpdate = false // future proof tests from breaking
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .withPluginClasspath()
      .build()
    def expected = """
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
 - com.google.inject:guice [2.0 -> 3.1]
     That's just the way it is
     https://code.google.com/p/google-guice/
 - com.google.inject.extensions:guice-multibindings [2.0 -> 3.0]
     https://code.google.com/p/google-guice/

Failed to determine the latest version for the following dependencies (use --info for details):
 - com.github.ben-manes:unresolvable
     Life is hard
 - com.github.ben-manes:unresolvable2
 - com.github.ben-manes:unresolvable3
""".replace('\r', '').replace('\n', System.lineSeparator())

    then:
    assert result.output.toString().contains(expected)
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
