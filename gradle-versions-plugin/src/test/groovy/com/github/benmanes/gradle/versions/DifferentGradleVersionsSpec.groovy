package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume
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
    def specVersion = System.getProperty("java.specification.version")
    def jdkMajor = specVersion.startsWith("1.") ? specVersion.split("\\.")[1].toInteger() : specVersion.toInteger()
    def gradleVersionParts = gradleVersion.tokenize('.').collect { it.toInteger() }
    def gradleMajor = gradleVersionParts[0]
    def gradleMinor = gradleVersionParts.size() > 1 ? gradleVersionParts[1] : 0
    // Gradle < 7.2 is incompatible with JDK 17+ (old Groovy runtime fails to initialize)
    if (gradleMajor < 7 || (gradleMajor == 7 && gradleMinor < 2)) {
      Assume.assumeTrue("Gradle ${gradleVersion} requires JDK < 17", jdkMajor < 17)
    }

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
    // Warning mode reporting only supported on recent versions
    // Gradle 8.x and 9.x deprecated configurations; ignore as unrelated
    def majorVersion = gradleMajor
    if ((majorVersion >= 6) && (majorVersion != 8) && (majorVersion < 9)) {
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
      '6.9.2',
      '7.0.2',
      '7.1.1',
      '7.2',
      '7.3.3',
      '7.4.2',
      '7.5.1',
      '7.6.4',
      '8.0.2',
      '8.1.1',
      '8.2.1',
      '8.3',
      '8.4',
      '8.5',
      '8.6',
      '8.7',
      '8.8',
      '8.9',
      '9.1.0',
      '9.2.1',
      '9.3.1',
    ]
  }

  def 'custom-named DependencyUpdatesTask reports updates'() {
    given:
    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

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
          implementation 'com.google.inject:guice:2.0'
        }

        tasks.register('dependencyUpdatesSummary', DependencyUpdatesTask) {
          outputDir = 'build/customReport'
        }
        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdatesSummary')
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdatesSummary').outcome == SUCCESS
  }

  def 'custom-named DependencyUpdatesTask at root reports subproject updates'() {
    given:
    def settingsFile = testProjectDir.newFile('settings.gradle')
    settingsFile << """
      rootProject.name = 'test-root'
      include 'sub1'
    """.stripIndent()

    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        subprojects {
          apply plugin: 'java'
          apply plugin: "com.github.ben-manes.versions"

          repositories {
            maven {
              url '${mavenRepoUrl}'
            }
          }

          dependencies {
            implementation 'com.google.inject:guice:2.0'
          }
        }

        tasks.register('dependencyUpdatesSummary', DependencyUpdatesTask) {
          outputDir = 'build/customReport'
        }
        """.stripIndent()

    testProjectDir.newFolder("sub1")
    testProjectDir.newFile("sub1/build.gradle") << ""

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdatesSummary')
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
    result.task(':dependencyUpdatesSummary').outcome == SUCCESS
  }

  @Unroll
  def 'subproject dependencyUpdates tasks find outdated deps when root delegates via plain lifecycle tasks with Gradle #gradleVersion'() {
    given:
    def specVersion = System.getProperty("java.specification.version")
    def jdkMajor = specVersion.startsWith("1.") ? specVersion.split("\\.")[1].toInteger() : specVersion.toInteger()
    def gradleMajor = gradleVersion.substring(0, gradleVersion.indexOf('.')).toInteger()
    if (gradleMajor >= 9) {
      Assume.assumeTrue("Gradle ${gradleVersion} requires JDK 17+", jdkMajor >= 17)
    }

    def settingsFile = testProjectDir.newFile('settings.gradle')
    settingsFile << """
      rootProject.name = 'test-root'
      include 'sub1', 'sub2'
    """.stripIndent()

    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        // Mirror real-world setup: plugin applied to subprojects only, not root
        subprojects {
          apply plugin: 'java'
          apply plugin: "com.github.ben-manes.versions"

          repositories {
            maven { url '${mavenRepoUrl}' }
          }

          dependencies {
            implementation 'com.google.inject:guice:2.0'
          }
        }

        def isNonStable = { String v ->
          def stableKeyword = ['RELEASE','FINAL','GA'].any { v?.toUpperCase()?.contains(it) }
          def regex = /^[0-9,.v-]+(-r)?\$/
          !stableKeyword && !(v ==~ regex)
        }

        // Second subprojects block: custom formatter + resolution strategy
        subprojects {
          tasks.matching { it.name == "dependencyUpdates" }.configureEach {
            checkForGradleUpdate = false
            def projectName = project.name
            def markerFile = rootProject.layout.buildDirectory
                .file("dependencyUpdates/.hasOutdated").get().asFile

            outputFormatter = { result ->
              def outdated = result.outdated.dependencies
              if (outdated) {
                markerFile.parentFile.mkdirs()
                markerFile.text = 'true'
                println "\${projectName}:"
                outdated.each { dep ->
                  def latest = dep.available.release ?: dep.available.milestone ?: dep.available.integration
                  println "  \${dep.group}:\${dep.name} \${dep.version} -> \${latest}"
                }
              }
            }

            resolutionStrategy {
              componentSelection {
                all { s ->
                  if (isNonStable(s.candidate.version) && !isNonStable(s.currentVersion)) {
                    s.reject('Release candidate')
                  }
                }
              }
            }
          }
        }

        // Summary task that checks the marker file
        tasks.register('dependencyUpdatesSummary') {
          description = 'Prints a summary after all dependency update checks'
          def markerFile = rootProject.layout.buildDirectory
              .file("dependencyUpdates/.hasOutdated").get().asFile
          dependsOn allprojects.collect { p ->
            p.tasks.matching { it.name == 'dependencyUpdates' }
          }
          doLast {
            if (!markerFile.exists()) {
              println 'All dependencies are up-to-date.'
            }
            markerFile.delete()
          }
        }

        // Root lifecycle task delegates to subprojects
        tasks.register('dependencyUpdates') {
          description = 'Runs dependency update checks for all subprojects'
          group = 'Help'
          dependsOn subprojects.collect { p ->
            p.tasks.matching { it.name == 'dependencyUpdates' }
          }
          finalizedBy tasks.named('dependencyUpdatesSummary')
        }
        """.stripIndent()

    testProjectDir.newFolder("sub1")
    testProjectDir.newFile("sub1/build.gradle") << ""
    testProjectDir.newFolder("sub2")
    testProjectDir.newFile("sub2/build.gradle") << ""

    when:
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '-Drevision=release', '--configuration-cache')
      .build()

    then:
    result.output.contains('com.google.inject:guice 2.0 ->')
    !result.output.contains('All dependencies are up-to-date')
    result.task(':dependencyUpdatesSummary').outcome == SUCCESS

    where:
    gradleVersion << ['8.9', '9.1.0', '9.2.1', '9.3.1']
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
          implementation 'com.google.inject:guice:2.0'
        }

        dependencyUpdates.gradleReleaseChannel="${gradleReleaseChannel}"

        """.stripIndent()

    when:
    def result = GradleRunner.create()
      .withGradleVersion('7.4.2') // 7.5.1+ breaks, keep 1 version behind
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
    def specVersion = System.getProperty("java.specification.version")
    def jdkMajor = specVersion.startsWith("1.") ? specVersion.split("\\.")[1].toInteger() : specVersion.toInteger()
    // This test uses Gradle 6.2 which is incompatible with JDK 17+
    Assume.assumeTrue("Gradle 6.2 requires JDK < 17", jdkMajor < 17)

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
      .withGradleVersion('6.2') // for dependency verification
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates')
      .build()

    then:
    result.output.contains('Dependency verification is an incubating feature.')
    result.output.contains('com.google.inject:guice [3.0 -> 3.1]')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  def 'dependencyUpdates task completes without errors if configuration cache is enabled with Gradle 7.4+'() {
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
      .withProjectDir(testProjectDir.root)
      .withArguments('dependencyUpdates', '--configuration-cache')
      .build()

    then:
    result.output.contains('BUILD SUCCESSFUL')
    result.task(':dependencyUpdates').outcome == SUCCESS
  }

  @Unroll
  def 'dependencyUpdates task completes with configuration cache enabled in multi-project build with Gradle #gradleVersion'() {
    given:
    def specVersion = System.getProperty("java.specification.version")
    def isJdk17Plus = !specVersion.startsWith("1.") && Integer.parseInt(specVersion) >= 17
    Assume.assumeTrue("Gradle 9.x requires JDK 17+", isJdk17Plus)

    def settingsFile = testProjectDir.newFile('settings.gradle')
    settingsFile << """
      rootProject.name = 'test-root'
      include 'sub1', 'sub2'
    """.stripIndent()

    buildFile = testProjectDir.newFile('build.gradle')
    buildFile <<
      """
        buildscript {
          dependencies {
            classpath files($classpathString)
          }
        }

        subprojects {
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
        }

        allprojects {
          tasks.matching { it.name == "dependencyUpdates" }.configureEach {
            // Pre-resolve project.name at configuration time to avoid Task.project at execution time
            def projName = project.name
            outputFormatter = { result ->
              def outdated = result.outdated.dependencies
              if (outdated) {
                println "\${projName}:"
                outdated.each { dep ->
                  def latest = dep.available.release ?: dep.available.milestone ?: dep.available.integration
                  println "  \${dep.group}:\${dep.name} \${dep.version} -> \${latest}"
                }
              }
            }

            resolutionStrategy {
              componentSelection {
                all { s ->
                  def v = s.candidate.version
                  def stableKeyword = ['RELEASE','FINAL','GA'].any { v?.toUpperCase()?.contains(it) }
                  def regex = /^[0-9,.v-]+(-r)?\$/
                  def isNonStable = !stableKeyword && !(v ==~ regex)
                  if (isNonStable && !(['RELEASE','FINAL','GA'].any { s.currentVersion?.toUpperCase()?.contains(it) }) && (s.currentVersion ==~ regex)) {
                    s.reject('Release candidate')
                  }
                }
              }
            }
          }
        }
        """.stripIndent()

    testProjectDir.newFolder("gradle")
    testProjectDir.newFolder("sub1")
    testProjectDir.newFile("sub1/build.gradle") << ""
    testProjectDir.newFolder("sub2")
    testProjectDir.newFile("sub2/build.gradle") << ""

    when:
    def result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withGradleVersion(gradleVersion)
      .withArguments('dependencyUpdates', '--configuration-cache')
      .build()

    then:
    result.output.contains('BUILD SUCCESSFUL')
    result.task(':sub1:dependencyUpdates').outcome == SUCCESS
    result.task(':sub2:dependencyUpdates').outcome == SUCCESS
    !result.output.contains('problems were found storing the configuration cache')

    where:
    gradleVersion << [
      '9.1.0',
      '9.2.1',
      '9.3.1',
    ]
  }

  @Unroll
  def 'dependencyUpdates task completes with configuration cache enabled with Gradle #gradleVersion'() {
    given:
    def specVersion = System.getProperty("java.specification.version")
    def jdkMajor = specVersion.startsWith("1.") ? specVersion.split("\\.")[1].toInteger() : specVersion.toInteger()
    def gradleVersionParts = gradleVersion.tokenize('.').collect { it.toInteger() }
    def gradleMajor = gradleVersionParts[0]
    def gradleMinor = gradleVersionParts.size() > 1 ? gradleVersionParts[1] : 0
    // Gradle < 7.2 is incompatible with JDK 17+ (old Groovy runtime fails to initialize)
    if (gradleMajor < 7 || (gradleMajor == 7 && gradleMinor < 2)) {
      Assume.assumeTrue("Gradle ${gradleVersion} requires JDK < 17", jdkMajor < 17)
    }

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
    arguments.add('-S')
    arguments.add('--configuration-cache')
    def result = GradleRunner.create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.root)
      .withArguments(arguments)
      .build()

    then:
    result.output.contains('com.google.inject:guice [2.0 -> 3.1]')
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
      '9.1.0',
      '9.2.1',
      '9.3.1',
    ]
  }
}
