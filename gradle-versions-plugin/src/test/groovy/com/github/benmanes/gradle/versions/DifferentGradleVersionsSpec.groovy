package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

final class DifferentGradleVersionsSpec extends Specification {
  @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
  private File buildFile
  private String classpathString
  private String mavenRepoUrl

  def 'setup'() {
    classpathString = PluginClasspath.asFilesArgument()
    mavenRepoUrl = getClass().getResource('/maven/').toURI()
  }

  // On JVMs below 17, Gradle 8.11+ emits its own deprecation for running on that JVM, so the
  // rows with warnings fatal only run on Java 17.
  @IgnoreIf({ !GradleVersions.drivenBy(data.gradleVersion) })
  @IgnoreIf({ data.warningMode == 'fail' && !jvm.java17Compatible })
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
        apply plugin: "io.github.ben-manes.versions"

        repositories {
          maven {
            url = '${mavenRepoUrl}'
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
    // Gradle 8.3 through 8.10 warns that the copied query configurations are deprecated for
    // dependency declaration, so those versions cannot run under --warning-mode=fail. The
    // configuration role that emitted it was dropped in 8.11, and the copies are the only
    // deprecation the plugin produces, so later versions run with warnings fatal.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/749
    def result = TestKitRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '-S', "--warning-mode=$warningMode")
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.0]')
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    gradleVersion          | warningMode
    '8.4'                  | 'all'
    '8.5'                  | 'all'
    '8.6'                  | 'all'
    '8.7'                  | 'all'
    '8.8'                  | 'all'
    '8.9'                  | 'all'
    '8.10'                 | 'all'
    '8.11.1'               | 'fail'
    GradleVersions.CURRENT | 'fail'
  }

  @IgnoreIf({ !GradleVersions.drivenBy('8.4') })
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
        apply plugin: "io.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates.gradleReleaseChannel="${gradleReleaseChannel}"

        """.stripIndent()

    when:
    def result = TestKitRunner.create()
      .withGradleVersion('8.4') // the running version must be behind the release channels
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

  @IgnoreIf({ !GradleVersions.drivenBy(data.gradleVersion) })
  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1095')
  def 'dependencyUpdates task works with dependency verification enabled on Gradle #gradleVersion'() {
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
        apply plugin: "io.github.ben-manes.versions"

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
                    <sha256 value="c7b675323937a405cca813590200a8df27080e6688b2187069524d14f5519579" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google.inject" name="guice" version="3.0">
                 <artifact name="guice-3.0.jar">
                    <sha256 value="1a59d0421ffd355cc0b70b42df1c2e9af744c8a2d0c92da379f5fca2f07f1d22" origin="Generated by Gradle"/>
                 </artifact>
                 <artifact name="guice-3.0.pom">
                    <sha256 value="6b075d9ccc2fcf5db92aab5fe85c8e3851b46a15c55211797ad4be92b2904234" origin="Generated by Gradle"/>
                 </artifact>
              </component>
              <component group="com.google.inject" name="guice-parent" version="3.0">
                 <artifact name="guice-parent-3.0.pom">
                    <sha256 value="5679af915032a07999c43e110f28c4014f5b17241ec86bf674fb28643be7cb60" origin="Generated by Gradle"/>
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
    // The candidate version's metadata cannot be in the user's verification file by construction,
    // so the lookups must be exempt from verification. Gradle 8.7 and later re-report a failure
    // recorded during those lookups at the next artifact access, which is the plugin's own
    // aggregation configuration.
    def result = TestKitRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS

    where:
    // 8.6 is the last release that passes without the exemption; every later one needs it.
    gradleVersion << ['8.6', '8.7', '8.14.4', GradleVersions.CURRENT]
  }

  @IgnoreIf({ !GradleVersions.drivenBy('8.14.4') })
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1095')
  def 'dependencyUpdates task leaves the verification opt out alone where the build has no metadata'() {
    given: 'a build with no verification metadata, which verifies nothing'
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        apply plugin: 'java'
        apply plugin: "io.github.ben-manes.versions"

        repositories {
          maven {
            url '${mavenRepoUrl}'
          }
        }

        dependencies {
          implementation 'com.google.inject:guice:3.0'
        }
        """.stripIndent()

    when: 'the oldest release that needs the exemption runs it, so every JVM leg covers this'
    def result = TestKitRunner.create()
      .withGradleVersion('8.14.4')
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then: 'Gradle prints its opt out line for every configuration that opts out, so none opts out'
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    !result.output.contains('Dependency verification has been disabled')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'dependencyUpdates task completes without errors if configuration cache is enabled'() {
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
        apply plugin: "io.github.ben-manes.versions"

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
    def result = TestKitRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--configuration-cache')
      .build()

    then:
    result.output.contains('BUILD SUCCESSFUL')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }
}
