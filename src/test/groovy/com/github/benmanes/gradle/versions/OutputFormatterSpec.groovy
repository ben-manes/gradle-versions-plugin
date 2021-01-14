package com.github.benmanes.gradle.versions

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import groovy.json.JsonSlurper
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
    def srdErrWriter = new StringWriter()

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
          compile 'com.google.inject:guice:2.0'
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
      .forwardStdError(srdErrWriter)
      .build()

    then:
    !result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    srdErrWriter.toString().empty
    result.task(':dependencyUpdates').outcome == SUCCESS
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
          compile 'com.google.inject:guice:2.0'
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
          compile 'com.google.inject:guice:2.0'
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
         http://code.google.com/p/google-guice/
      """.stripIndent()
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
          compile 'com.google.inject:guice:2.0'
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
                    "version": "2.0",
                    "projectUrl": "http://code.google.com/p/google-guice/",
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
        "count": 1
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
          compile 'com.google.inject:guice:2.0'
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
      <count>1</count>
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
            <projectUrl>http://code.google.com/p/google-guice/</projectUrl>
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
}
