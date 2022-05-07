package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

final class DifferentGradleVersionsSpec extends Specification {

  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private List<File> pluginClasspath
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
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  @Unroll
  def 'dependencyUpdates task completes without errors with Gradle #gradleVersion'() {
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
    arguments.add('-S')
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    gradleVersion << [
      '5.0',
      '5.1.1',
      '5.2.1',
      '5.3.1',
      '5.4.1',
      '5.5.1',
      '5.6.4',
      '6.0.1',
      '6.1.1',
      '6.2.2',
      '6.3',
      '6.4',
      '6.4.1',
      '6.5.1',
      '6.6.1',
      '6.7.1',
      '6.8.3',
    ]
  }

  @Unroll
  def 'dependencyUpdates task uses specified release channel with Gradle #gradleReleaseChannel'() {
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
      .withGradleVersion('5.0')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains("Gradle ${gradleReleaseChannel} updates:")
    !result.output.contains("UP-TO-DATE")
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    gradleReleaseChannel << [
      CURRENT.id,
      RELEASE_CANDIDATE.id,
      NIGHTLY.id
    ]
  }

  def 'dependencyUpdates task works with dependency verification enabled'() {
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
        apply plugin: "com.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          compile 'com.google.inject:guice:3.0'
        }
        """.stripIndent()

    testProjectDir.newFolder("gradle")
    def verificationFile = testProjectDir.newFile('gradle/verification-metadata.xml')
    verificationFile <<
      """<?xml version="1.0" encoding="UTF-8"?>
        <verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="https://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.0.xsd">
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
              <component group="asm" name="asm" version="3.1">
                 <artifact name="asm-3.1.jar">
                    <sha256 value="333ff5369043975b7e031b8b27206937441854738e038c1f47f98d072a20437a" origin="Generated by Gradle"/>
                 </artifact>
                 <artifact name="asm-3.1.pom">
                    <sha256 value="0dc0b75a076259ce70a1d6d148f357651d6b698adb42c44c738612de76af6fcc" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="asm" name="asm-parent" version="3.1">
                 <artifact name="asm-parent-3.1.pom">
                    <sha256 value="7367b4cd7b73c25acd4e566d9cee02313dafe9ef34c9e6af14c52c019669d4a2" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google" name="google" version="5">
                 <artifact name="google-5.pom">
                    <sha256 value="e09d345e73ca3fbca7f3e05f30deb74e9d39dd6b79a93fee8c511f23417b6828" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google.inject" name="guice" version="3.0">
                 <artifact name="guice-3.0.jar">
                    <sha256 value="1a59d0421ffd355cc0b70b42df1c2e9af744c8a2d0c92da379f5fca2f07f1d22" origin="Generated by Gradle"/>
                 </artifact>
                 <artifact name="guice-3.0.pom">
                    <sha256 value="2288280c645a16e6a649119b3f43ebcac2a698216b805b2c6f0eeea39191edc0" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google.inject" name="guice-parent" version="3.0">
                 <artifact name="guice-parent-3.0.pom">
                    <sha256 value="5c6e38c35984e55033498fc22d1519b2501c36abf089fd445081491f6fcce91a" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="javax.inject" name="javax.inject" version="1">
                 <artifact name="javax.inject-1.jar">
                    <sha256 value="91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff" origin="Generated by Gradle"/>
                 </artifact>
                 <artifact name="javax.inject-1.pom">
                    <sha256 value="943e12b100627804638fa285805a0ab788a680266531e650921ebfe4621a8bfa" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="org.sonatype.forge" name="forge-parent" version="6">
                 <artifact name="forge-parent-6.pom">
                    <sha256 value="9c5f7cd5226ac8c3798cb1f800c031f7dedc1606dc50dc29567877c8224459a7" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="org.sonatype.sisu.inject" name="cglib" version="2.2.1-v20090111">
                 <artifact name="cglib-2.2.1-v20090111.jar">
                    <sha256 value="42e1dfb26becbf1a633f25b47e39fcc422b85e77e4c0468d9a44f885f5fa0be2" origin="Generated by Gradle"/>
                 </artifact>
                 <artifact name="cglib-2.2.1-v20090111.pom">
                    <sha256 value="4af35547bb5db3e49fb750865af0e333afdc82e6e6d7d8adbd1c1411dfad6081" origin="Generated by Gradle"/>
                 </artifact>
              </component>
           </components>
        </verification-metadata>
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('6.2')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains('Dependency verification is an incubating feature.')
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'dependencyUpdates task completes without errors if configuration cache is enabled with Gradle 7.4'() {
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
        apply plugin: "com.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:3.0'
        }
        """.stripIndent()

    testProjectDir.newFolder("gradle")

    when:
    def result = GradleRunner.create()
      .withGradleVersion('7.4-rc-2')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--configuration-cache')
      .build()

    then:
    result.output.contains('BUILD SUCCESSFUL')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Unroll
  def 'dependencyUpdates task fails if configuration cache is enabled with Gradle #gradleVersion'() {
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
        """.stripIndent()

    when:
    def arguments = ['dependencyUpdates']
    // Warning mode reporting only supported on recent versions.
    if (gradleVersion.substring(0, gradleVersion.indexOf('.')).toInteger() >= 6) {
      arguments.add('--warning-mode=fail')
    }
    arguments.add('-S')
    arguments.add('--configuration-cache')
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .buildAndFail()

    then:
    result.output.contains('FAILURE: Build failed with an exception.')
    result.output.contains('Configuration cache problems found in this build.')
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    gradleVersion << [
      '6.6.1',
      '6.7.1',
      '6.8.3',
      '6.9.2',
      '7.0.2',
      '7.1.1',
      '7.2',
      '7.3.3',
    ]
  }
}
