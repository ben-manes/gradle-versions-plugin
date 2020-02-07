package com.github.benmanes.gradle.versions

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.*

final class DifferentGradleVersionsSpec extends Specification {

  @Rule TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath

  def 'setup'() {
    def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException(
        "Did not find plugin classpath resource, run `testClasses` build task.")
    }

    pluginClasspath = pluginClasspathResource.readLines().collect { new File(it) }
  }

  @Unroll
  def 'dependencyUpdates task completes without errors with Gradle #gradleVersion'() {
    given:
    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
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
        apply plugin: "com.github.ben-manes.versions"

        // Gradle 7.0+ do not allow directly using compile configuration so we monkey patch
        // an implementation configuration in for older Gradle versions.
        if (configurations.findByName('implementation') == null) {
          configurations.create('implementation') {
            extendsFrom configurations.compile
          }
        }

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates.resolutionStrategy {
          componentSelection {
            all {
              if (candidate.version == "3.1" && currentVersion == "2.0") {
                reject("Guice 3.1 not allowed")
              }
            }
          }
        }
        """.stripIndent()

    when:
    def arguments = ['dependencyUpdates']
    // Warning mode reporting only supported on recent versions.
    if (gradleVersion.substring(0, gradleVersion.indexOf('.')).toInteger() >= 6) {
      arguments.add('--warning-mode=fail')
    }
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    srdErrWriter.toString().empty

    where:
    gradleVersion << [
      '3.3',
      '3.4',
      '3.5',
      '4.0',
      '4.1',
      '4.2',
      '4.3',
      '4.4',
      '4.5',
      '4.6',
      '4.7',
      '4.8',
      '4.9',
      '4.10',
      '5.0',
      '5.1.1',
      '5.2.1',
      '5.3.1',
      '5.4.1',
      '5.5.1',
      '5.6',
      '6.0',
      '6.1.1',
      '6.2-rc-1',
    ]
  }

  @Unroll
  def 'dependencyUpdates task uses specified release channel with Gradle #gradleReleaseChannel'() {
    given:
    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
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
        apply plugin: "com.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          compile 'com.google.inject:guice:2.0'
        }

        dependencyUpdates.gradleReleaseChannel="${gradleReleaseChannel}"

        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('3.3')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains("Gradle ${gradleReleaseChannel} updates:")
    !result.output.contains("UP-TO-DATE")
    srdErrWriter.toString().empty

    where:
    gradleReleaseChannel << [
      CURRENT.id,
      RELEASE_CANDIDATE.id,
      NIGHTLY.id
    ]
  }

  def 'dependencyUpdates task works with dependency verification enabled'() {
    given:
    def classpathString = pluginClasspath
      .collect { it.absolutePath.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }
      .join(", ")
    def mavenRepoUrl = getClass().getResource('/maven/').toURI()
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
        apply plugin: "com.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          compile 'com.google.inject:guice:2.0'
        }
        """.stripIndent()

    testProjectDir.newFolder("gradle")
    def verificationFile = testProjectDir.newFile('gradle/verification-metadata.xml')
    verificationFile <<
      """<?xml version="1.0" encoding="UTF-8"?>
        <verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.0.xsd">
           <configuration>
              <verify-metadata>true</verify-metadata>
              <verify-signatures>false</verify-signatures>
           </configuration>
           <components>
              <component group="aopalliance" name="aopalliance" version="1.0">
                 <artifact name="aopalliance-1.0.jar">
                    <sha256 value="0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08" origin="Generated by Gradle"/>
                 </artifact>
                 <artifact name="aopalliance-1.0.pom">
                    <sha256 value="26e82330157d6b844b67a8064945e206581e772977183e3e31fec6058aa9a59b" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google" name="google" version="1">
                 <artifact name="google-1.pom">
                    <sha256 value="cd6db17a11a31ede794ccbd1df0e4d9750f640234731f21cff885a9997277e81" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google.inject" name="guice" version="2.0">
                 <artifact name="guice-2.0.jar">
                    <sha256 value="9fb545199584a41e8064e1232ca3f3c757366b27fc7aa488ac0fc98263642756" origin="Generated by Gradle"/>
                 </artifact>
                 <artifact name="guice-2.0.pom">
                    <sha256 value="6a121e2334b23884eb06bcdb4d0eb0dad3fab75d23029737a0e8dd65231a6899" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google.inject" name="guice-parent" version="2.0">
                 <artifact name="guice-parent-2.0.pom">
                    <sha256 value="9c4b45c665b1e423de3e0be6491f10d3201c709d6cb96bf951a136d9061265b4" origin="Generated by Gradle"/>
                 </artifact>
              </component>
           </components>
        </verification-metadata>

        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('6.2-rc-1')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .forwardStdError(srdErrWriter)
      .build()

    then:
    result.output.contains('Dependency verification is an incubating feature.')
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    srdErrWriter.toString().empty
  }
}
