package com.github.benmanes.gradle.versions

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.updates.OutputFormatterArgument
import groovy.json.JsonSlurper
import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue
import spock.lang.Specification

/**
 * A specification for reporting each declared version against the latest version found for it.
 */
final class ReportJoinSpec extends Specification {

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/348',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/906',
  ])
  def 'An outdated version is reported against the latest version found for it'() {
    given:
    def root = twoProjects('com.google.inject', 'guice', '3\\.1', '2.0', '3.0')

    when:
    def result = evaluate(root)

    then:
    result.current.dependencies.isEmpty()
    with(result.outdated.dependencies.toList()) {
      it*.version == ['2.0', '3.0']
      it*.available*.milestone == ['3.0', '3.1']
    }
  }

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/348',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/906',
  ])
  def 'Multiple up to date versions of a module all survive into the report'() {
    given:
    def root = twoProjects('org.apache.logging.log4j', 'log4j-core', '2\\.17\\.0', '2.16.0', '2.17.0')

    when:
    def result = evaluate(root)

    then:
    result.current.dependencies*.version == ['2.16.0', '2.17.0']
    result.current.dependencies.every {
      it.group == 'org.apache.logging.log4j' && it.name == 'log4j-core'
    }
    result.outdated.dependencies.isEmpty()
  }

  @Issue([
    'https://github.com/ben-manes/gradle-versions-plugin/issues/348',
    'https://github.com/ben-manes/gradle-versions-plugin/issues/906',
  ])
  def 'Multiple up to date versions of a module all survive into the report file'() {
    given:
    def root = twoProjects('org.apache.logging.log4j', 'log4j-core', '2\\.17\\.0', '2.16.0', '2.17.0')

    when:
    writeReport(root, 'json')

    then:
    def report = new JsonSlurper().parse(new File(root.projectDir, 'build/report.json'))
    report.current.dependencies*.version == ['2.16.0', '2.17.0']
    report.count == 2
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
  def 'A divergent version is printed with its declaring projects'() {
    given:
    def root = twoProjects('com.google.inject', 'guice', '3\\.1', '2.0', '3.0')

    when:
    def result = evaluate(root)

    then:
    result.outdated.dependencies*.projects == [[':pinned'], [':open']]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
  def 'A shared version is printed with no projects'() {
    given:
    def root = twoProjects('com.google.inject', 'guice', 'nomatch', '3.1', '3.1')

    when:
    def result = evaluate(root)

    then:
    result.current.dependencies*.projects == [null]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1032')
  def 'Attributes a buildscript dependency to its project'() {
    given:
    def root = ProjectBuilder.builder().withName('root').build()
    def app = ProjectBuilder.builder().withName('app').withParent(root).build()
    def localMavenRepo = getClass().getResource('/maven/')
    for (project in [root, app]) {
      project.buildscript.repositories {
        maven {
          url localMavenRepo.toURI()
        }
      }
    }
    root.buildscript.dependencies.add('classpath', 'com.google.inject:guice:2.0')
    app.buildscript.dependencies.add('classpath', 'com.google.inject:guice:3.0')

    when:
    def result = evaluate(root)

    then:
    result.outdated.dependencies*.version.sort() == ['2.0', '3.0']
    result.outdated.dependencies.find { it.version == '2.0' }.projects == [':']
    result.outdated.dependencies.find { it.version == '3.0' }.projects == [':app']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1028')
  def 'A version declared by any project is not marked contributed'() {
    given:
    def root = ProjectBuilder.builder().withName('root').build()
    def a = ProjectBuilder.builder().withName('a').withParent(root).build()
    def b = ProjectBuilder.builder().withName('b').withParent(root).build()
    def localMavenRepo = getClass().getResource('/maven/')
    for (project in [a, b]) {
      project.repositories {
        maven {
          url localMavenRepo.toURI()
        }
      }
    }
    a.configurations {
      app
    }
    a.dependencies {
      app 'com.google.inject:guice:3.0'
    }
    b.configurations.create('tool') {
      canBeResolved = true
      canBeConsumed = false
    }
    b.configurations.tool.defaultDependencies { deps ->
      deps.add(b.dependencies.create('com.google.inject:guice:3.0'))
    }

    when:
    def result = evaluate(root)

    then:
    result.outdated.dependencies.find { it.name == 'guice' }.contributed == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1050')
  def 'A version another project resolved is reported with the version it found'() {
    given:
    def root = blindAndSeeing()

    when:
    def result = evaluate(root)

    then:
    result.outdated.dependencies*.version == ['2.0']
    result.outdated.dependencies.first().available.milestone == '3.1'
    // The resolution that failed is still reported, as it is the user's to see.
    result.unresolved.dependencies*.name == ['guice']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1050')
  def 'A version that no project resolved is still reported as unresolved'() {
    given:
    // Only 2.0 is answered, so the failure on 3.0 is another version of an answered module.
    def root = blindAndSeeing('3.0')

    when:
    def result = evaluate(root)

    then:
    result.unresolved.dependencies*.name == ['guice']
    result.outdated.dependencies*.version == ['2.0']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1050')
  def 'A version declared without one is undeclared when a resolution answered for it'() {
    given:
    def root = ProjectBuilder.builder().withName('root').build()
    // The script classpath resolves the version-less declaration and the project cannot, so
    // the module is both declared without a version and one a resolution failed on.
    root.buildscript.repositories {
      maven {
        url getClass().getResource('/maven/').toURI()
      }
    }
    root.buildscript.dependencies.add('classpath', 'com.google.inject:guice')
    def blind = ProjectBuilder.builder().withName('blind').withParent(root).build()
    blind.configurations {
      app
    }
    blind.dependencies.add('app', 'com.google.inject:guice')

    when:
    def result = evaluate(root)

    then:
    result.undeclared.dependencies*.name == ['guice']
    result.unresolved.dependencies*.name == ['guice']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1050')
  def 'A version reported against a failure is counted in each section it appears in'() {
    given:
    def root = blindAndSeeing()

    when:
    writeReport(root, 'json')

    then:
    def report = new JsonSlurper().parse(new File(root.projectDir, 'build/report.json'))
    report.outdated.dependencies*.version == ['2.0']
    report.unresolved.dependencies*.name == ['guice']
    // The one module is reported twice, so the total counts it once per section it appears in.
    report.count == 2
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1050')
  def 'A project that could not resolve a version is still printed on its row'() {
    given:
    def root = blindAndSeeing()
    def ahead = ProjectBuilder.builder().withName('ahead').withParent(root).build()
    ahead.repositories {
      maven {
        url getClass().getResource('/maven/').toURI()
      }
    }
    ahead.configurations {
      app
    }
    ahead.dependencies {
      app 'com.google.inject:guice:3.0'
    }

    when:
    def result = evaluate(root)

    then:
    // Dropping the blind project's status outright would leave 2.0 printed against :seeing alone.
    result.outdated.dependencies.find { it.version == '2.0' }.projects == [':blind', ':seeing']
    result.outdated.dependencies.find { it.version == '3.0' }.projects == [':ahead']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1050')
  def 'A version a project declared but could not resolve is not marked contributed'() {
    given:
    def root = ProjectBuilder.builder().withName('root').build()
    def blind = ProjectBuilder.builder().withName('blind').withParent(root).build()
    def seeing = ProjectBuilder.builder().withName('seeing').withParent(root).build()
    seeing.repositories {
      maven {
        url getClass().getResource('/maven/').toURI()
      }
    }
    // Declared by the project with no repository to resolve it against, and contributed by a
    // plugin in the project that can, so only the failing project's status marks it as declared.
    blind.configurations {
      app
    }
    blind.dependencies {
      app 'com.google.inject:guice:2.0'
    }
    seeing.configurations.create('tool') {
      canBeResolved = true
      canBeConsumed = false
    }
    seeing.configurations.tool.defaultDependencies { deps ->
      deps.add(seeing.dependencies.create('com.google.inject:guice:2.0'))
    }

    when:
    def result = evaluate(root)

    then:
    result.outdated.dependencies.find { it.name == 'guice' }.contributed == null
  }

  /**
   * A root project whose two children declare the same module, where only {@code :seeing} has a
   * repository to resolve it against. It resolves 2.0, while {@code :blind} resolves nothing.
   */
  private def blindAndSeeing(String blindVersion = '2.0') {
    def root = ProjectBuilder.builder().withName('root').build()
    def blind = ProjectBuilder.builder().withName('blind').withParent(root).build()
    def seeing = ProjectBuilder.builder().withName('seeing').withParent(root).build()
    seeing.repositories {
      maven {
        url getClass().getResource('/maven/').toURI()
      }
    }
    for (project in [blind, seeing]) {
      project.configurations {
        app
      }
    }
    blind.dependencies {
      app "com.google.inject:guice:$blindVersion"
    }
    seeing.dependencies {
      app 'com.google.inject:guice:2.0'
    }
    return root
  }

  /**
   * A root project whose two children declare the same module, where the first child's repository
   * hides the versions matching {@code hiddenVersionRegex}. The latest version found therefore
   * differs between the children, which a report keyed on the group and name alone cannot express.
   */
  private def twoProjects(String group, String artifact, String hiddenVersionRegex,
      String pinnedVersion, String openVersion) {
    def root = ProjectBuilder.builder().withName('root').build()
    def pinned = ProjectBuilder.builder().withName('pinned').withParent(root).build()
    def open = ProjectBuilder.builder().withName('open').withParent(root).build()
    def localMavenRepo = getClass().getResource('/maven/')
    pinned.repositories {
      maven {
        url localMavenRepo.toURI()
        content {
          excludeVersionByRegex(group.replace('.', '\\.'), artifact, hiddenVersionRegex)
        }
      }
    }
    open.repositories {
      maven {
        url localMavenRepo.toURI()
      }
    }
    for (project in [pinned, open]) {
      project.configurations {
        app
      }
    }
    pinned.dependencies {
      app "$group:$artifact:$pinnedVersion"
    }
    open.dependencies {
      app "$group:$artifact:$openVersion"
    }
    return root
  }

  private static Result evaluate(project) {
    Result captured = null
    reportWith(project,
      new OutputFormatterArgument.CustomAction({ result -> captured = result } as Action<Result>))
    return captured
  }

  private static void writeReport(project, String outputFormat) {
    reportWith(project, new OutputFormatterArgument.BuiltIn(outputFormat))
  }

  private static void reportWith(project, OutputFormatterArgument formatter) {
    ProjectEvaluator.evaluate(project, null, 'milestone', formatter, 'build', 'report', false,
      'https://services.gradle.org/versions/', RELEASE_CANDIDATE.id).write()
  }
}
