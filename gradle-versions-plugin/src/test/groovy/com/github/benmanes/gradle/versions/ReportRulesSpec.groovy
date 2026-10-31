package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.ReportRules
import com.github.benmanes.gradle.versions.updates.PartialStatus
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.RecordedComponentSelection
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.api.specs.Spec
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

/**
 * A specification for ReportRules, which replays the aggregating build's own component-selection
 * rules over each row's recorded candidates, starting at the row's baked verdict.
 * https://github.com/ben-manes/gradle-versions-plugin/issues/1058
 */
@Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
final class ReportRulesSpec extends Specification {
  private static final def LOGGER = Logging.getLogger(ReportRulesSpec)

  private static List<PartialStatus> applyRules(
    List<PartialStatus> statuses, Map<String, List<String>> candidates, Action strategy,
    String revision = 'milestone', boolean rejectPreReleases = false,
    Spec<String> preReleaseVersionIf = null, ComponentFilter exemptFromBuiltInChecksIf = null) {
    return new ReportRules(strategy, LOGGER, revision, rejectPreReleases, preReleaseVersionIf,
      exemptFromBuiltInChecksIf).applyTo(statuses, candidates)
  }

  private static PartialStatus statusOf(
    String group, String name, String declaredVersion, String latestVersion, String projectPath = ':',
    String projectUrl = null) {
    return new PartialStatus(group, name, declaredVersion, null, latestVersion, projectUrl, null, false, [], projectPath)
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

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'A candidate list that omits the walk verdict leaves the row undecided'() {
    given: 'a rule that would reject the baked verdict if it ever ran, but the row has no recorded candidates'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': []]

    when:
    def applied = applyRules([status], candidates, rejecting('2.0'))

    then: 'the baked verdict survives untouched, as the membership guard never ran the rule'
    applied == [status]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'A predicate reading metadata or the descriptor is answered null at the report'() {
    given:
    def selection = new RecordedComponentSelection('com.example', 'widget', '2.0')

    expect:
    selection.metadata == null
    selection.getDescriptor(String) == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Reports a row unresolved when every candidate is rejected'() {
    given: 'a rule that rejects every candidate the row recorded'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [':': ['com.example:widget:3.0', 'com.example:widget:2.0', 'com.example:widget:1.0']]
    def rejectAll = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('rejected by the test rule') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectAll)

    then:
    applied.size() == 1
    applied[0].latestVersion == 'none'
    applied[0].unresolved != null
    applied[0].unresolved.selectorGroup == 'com.example'
    applied[0].unresolved.selectorName == 'widget'
    applied[0].unresolved.failureText == 'rejected by the test rule'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Falls back to the fixed text when the rejecting rule gave no reason'() {
    given: 'a rule that rejects every candidate with an empty reason'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': ['com.example:widget:2.0', 'com.example:widget:1.0']]
    def rejectSilently = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectSilently)

    then:
    applied[0].unresolved.failureText == 'Rejected by the aggregating build\'s component selection rules'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The first rule to reject a candidate supplies the reason, not a later rule that never ran'() {
    given: 'two rules over the same candidate; the second never runs once the first has rejected'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': ['com.example:widget:2.0', 'com.example:widget:1.0']]
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection -> selection.reject('first reason') }
        rules.all { selection -> selection.reject('second reason') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then:
    applied[0].unresolved.failureText == 'first reason'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The reason reported is for the ceiling candidate rather than a lower one also rejected'() {
    given: 'a rule that rejects three candidates, each with its own reason'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates =
      [':': ['com.example:widget:3.0', 'com.example:widget:2.0', 'com.example:widget:1.0']]
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection ->
          selection.reject("rejected ${selection.candidate.version}".toString())
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then: 'the reason reported is the ceiling\'s (3.0, the row\'s own baked verdict), not 2.0\'s or 1.0\'s'
    applied[0].unresolved.failureText == 'rejected 3.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Matches recorded candidates by module, not by prefix'() {
    given: 'two modules with a common name prefix, only one of them targeted by the rule'
    def core = statusOf('com.example', 'core', '1.0', '2.0')
    def coreExt = statusOf('com.example', 'core-ext', '1.0', '2.0')
    def candidates = [
      ':': [
        'com.example:core:2.0', 'com.example:core:1.0',
        'com.example:core-ext:2.0', 'com.example:core-ext:1.0',
      ],
    ]
    def rejectCoreOnly = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.withModule('com.example:core') { selection ->
          if (selection.candidate.version == '2.0') {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([core, coreExt], candidates, rejectCoreOnly)

    then: 'only the targeted module is capped; the one with the same prefix is untouched'
    applied.find { it.name == 'core' }.latestVersion == '1.0'
    applied.find { it.name == 'core-ext' }.latestVersion == '2.0'
  }

  @Unroll
  def 'Matches a module notation written as #label'() {
    given: 'a rule targeting one module, its notation spelled the way a build script may spell it'
    def core = statusOf('com.example', 'core', '1.0', '2.0')
    def candidates = [':': ['com.example:core:2.0', 'com.example:core:1.0']]
    def rejectCore = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.withModule(notation) { selection ->
          if (selection.candidate.version == '2.0') {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([core], candidates, rejectCore)

    then: 'an interpolated notation is a GString rather than a String, and Gradle accepts either'
    applied[0].latestVersion == '1.0'

    where:
    label       | notation
    'a String'  | 'com.example:core'
    'a GString' | "${'com.example'}:core"
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Ignores a rejection that the rule made after reading the absent metadata'() {
    given: 'a rule rejecting every candidate because the record answers its metadata with null'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [':': ['com.example:widget:3.0', 'com.example:widget:2.0']]
    def rejectOnNullMetadata = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          if (selection.metadata == null) {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectOnNullMetadata)

    then: 'the row keeps the verdict its own build reached with the metadata the report cannot read'
    applied[0].latestVersion == '3.0'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Shows no candidate below one the rule rejected on the absent metadata'() {
    given: 'a rule rejecting the verdict by version, and everything under it by its null metadata'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates =
      [':': ['com.example:widget:3.0', 'com.example:widget:2.0', 'com.example:widget:1.0']]
    // Groovy short circuits, so the verdict is rejected without the metadata ever being read.
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == '3.0' || selection.metadata == null) {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then: 'the walk stops rather than offering 2.0, which the rule rejected as surely as 3.0'
    applied[0].latestVersion == '3.0'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Honors a later rule that rejects on the version after an earlier one read the metadata'() {
    given: 'a metadata reading rule that accepts, followed by one rejecting the verdict by version'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [':': ['com.example:widget:3.0', 'com.example:widget:2.0']]
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection -> selection.metadata }
        rules.all { selection ->
          if (selection.candidate.version == '3.0') {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, strategy)

    then: 'the metadata read by the earlier rule does not excuse the later rule from being applied'
    applied[0].latestVersion == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'A v1 partial with no candidates list leaves every row at its baked verdict'() {
    given: 'a partial from an older release, with no entry for its projectPath in the candidates map'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [:]
    def rejectAll = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('rejected by the test rule') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectAll)

    then: 'the missing candidate list is read as "row not recorded", not as every candidate rejected'
    applied == [status]
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def "Candidates recorded by one project are never applied to another project's row"() {
    given: 'two projects declaring the same module; only one recorded a candidate list at all'
    def rowInA = statusOf('com.example', 'widget', '1.0', '2.0', ':a')
    def rowInB = statusOf('com.example', 'widget', '1.0', '2.0', ':b')
    def candidates = [
      ':a': ['com.example:widget:2.0', 'com.example:widget:1.0'],
      ':b': [],
    ]

    when:
    def applied = applyRules([rowInA, rowInB], candidates, rejecting('2.0'))

    then: "project :a's row is walked down by its own recorded candidates"
    applied.find { it.projectPath == ':a' }.latestVersion == '1.0'

    and: "project :b's row, which recorded none, is untouched by :a's candidates"
    applied.find { it.projectPath == ':b' }.latestVersion == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'The user action executes exactly once, regardless of how many rows are applied'() {
    given: 'a strategy that counts its own executions, applied over three rows'
    def rows = [
      statusOf('com.example', 'a', '1.0', '2.0'),
      statusOf('com.example', 'b', '1.0', '2.0'),
      statusOf('com.example', 'c', '1.0', '2.0'),
    ]
    def candidates = [':': []]
    def executions = 0
    def strategy = { ResolutionStrategyWithCurrent strategy ->
      executions++
      strategy.componentSelection { rules -> rules.all { selection -> } }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    applyRules(rows, candidates, strategy)

    then:
    executions == 1
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Shows no integration candidate below the ceiling under a milestone revision'() {
    given: 'a listing with a snapshot between the rejected ceiling and the release below it'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [
      ':': ['com.example:widget:3.0', 'com.example:widget:2.5-SNAPSHOT', 'com.example:widget:2.0'],
    ]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0'), 'milestone')

    then: 'the walk steps over the snapshot a milestone report may not show'
    applied[0].latestVersion == '2.0'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/798')
  def 'Shows no snapshot candidate below the ceiling under a release revision'() {
    given: 'a snapshot and a jre qualified release below the ceiling the rule rejected'
    def status = statusOf('com.example', 'widget', '1.0', '3.0')
    def candidates = [
      ':': ['com.example:widget:3.0', 'com.example:widget:2.5-SNAPSHOT', 'com.example:widget:2.0.jre11'],
    ]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0'), 'release')

    then: 'the snapshot is stepped over and the jre qualified version is offered instead'
    applied[0].latestVersion == '2.0.jre11'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/475')
  def "The row's own version is offerable below the ceiling however unstable it reads"() {
    given: 'a build already on a snapshot, under a revision that rejects every other snapshot'
    def status = statusOf('com.example', 'widget', '1.0-SNAPSHOT', '3.0')
    def candidates = [
      ':': ['com.example:widget:3.0', 'com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.0-SNAPSHOT'],
    ]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0'), 'release')

    then: 'the newer snapshot is stepped over, but the version the build declares is exempt'
    applied[0].latestVersion == '1.0-SNAPSHOT'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Leaves the ceiling as its own build baked it, whatever its version string reads as'() {
    given: 'a snapshot ceiling the revision would reject below it, and a rule that rejects nothing'
    def status = statusOf('com.example', 'widget', '1.0', '2.0-SNAPSHOT')
    def candidates = [':': ['com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.5']]

    when:
    def applied = applyRules([status], candidates, rejecting('9.9'), 'release')

    then: 'the revision guard applies below the ceiling only, so the baked verdict is untouched'
    applied[0].latestVersion == '2.0-SNAPSHOT'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def 'Rechecks the ceiling itself once the report supplies a rule of its own'() {
    given: 'a snapshot ceiling a report rule rejects, and a release below it'
    def status = statusOf('com.example', 'widget', '1.0', '2.0-SNAPSHOT')
    def candidates = [':': ['com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.5']]

    when:
    def applied = applyRules([status], candidates, rejecting('2.0-SNAPSHOT'), 'release')

    then: 'the rule reaches the ceiling as well as the candidates below it, and the row drops to the release'
    applied[0].latestVersion == '1.5'
    applied[0].unresolved == null
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/550')
  def "Reports the rule that rejected every candidate starting at the ceiling"() {
    given: 'a listing of snapshots only, all rejected by a rule of the report\'s own'
    def status = statusOf('com.example', 'widget', '1.0', '2.0-SNAPSHOT')
    def candidates = [':': ['com.example:widget:2.0-SNAPSHOT', 'com.example:widget:1.5-SNAPSHOT']]
    def rejectAll = { ResolutionStrategyWithCurrent strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection -> selection.reject('rejected by the test rule') }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([status], candidates, rejectAll, 'release')

    then: 'the exhausted walk reports the ceiling\'s own rejection reason'
    applied[0].latestVersion == 'none'
    applied[0].unresolved.selectorVersion == '2.0-SNAPSHOT'
    applied[0].unresolved.failureText == 'rejected by the test rule'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Replays rules in registration order'() {
    given: 'two rules that each record their own invocation, in the order the build registered them'
    def status = statusOf('com.example', 'widget', '1.0', '2.0')
    def candidates = [':': ['com.example:widget:2.0', 'com.example:widget:1.0']]
    def order = []
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { order << 'first' }
        rules.all { order << 'second' }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    applyRules([status], candidates, strategy)

    then:
    order == ['first', 'second']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1058')
  def 'Walks by version order rather than the order the repositories were recorded in'() {
    given: 'a listing recorded per repository, so the verdict trails candidates older than it'
    def status = statusOf('com.example', 'widget', '1.0', '3.0-Beta1')
    def candidates =
      [':': ['com.example:widget:2.0', 'com.example:widget:1.0', 'com.example:widget:3.0-Beta1']]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0-Beta1'))

    then: 'the walk steps to the newest candidate below the verdict, not to the end of the list'
    applied[0].latestVersion == '2.0'
    applied[0].unresolved == null
  }

  def "The report's pre-release check rejects the ceiling its producer accepted"() {
    given: 'a row with a pre-release baked by its producer, the report checking pre-releases itself'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '1.0', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0',
                            'com.probe:unstable-ceiling:1.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true)

    then: 'the walk steps down to the newest candidate the check accepts'
    applied[0].latestVersion == '2.0'
    applied[0].unresolved == null
  }

  def "The report's own convention is part of the check it applies"() {
    given: 'versions no built-in marker covers, and a convention matching them'
    def statuses = [statusOf('com.example', 'prerelease-flagged', '1.0', '3.0-flagged')]
    def candidates = [':': ['com.example:prerelease-flagged:3.0-flagged',
                            'com.example:prerelease-flagged:2.0-flagged',
                            'com.example:prerelease-flagged:1.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true,
      { String version -> version.endsWith('-flagged') } as Spec<String>)

    then: 'both flagged candidates are held, leaving the version the build already declares'
    applied[0].latestVersion == '1.0'
    applied[0].unresolved == null
  }

  def "An exemption keeps a candidate the report's check would reject"() {
    given: 'the same pre-release ceiling, with the module exempted from the built-in checks'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '1.0', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true, null,
      { current -> current.candidate.module == 'unstable-ceiling' } as ComponentFilter)

    then: 'the ceiling stands, as it does for a build that exempts the module at its producer'
    applied[0].latestVersion == '3.0-Beta1'
  }

  def 'An exemption answering on the absent metadata leaves the row as its producer reported it'() {
    given: 'an exemption that reads the metadata absent from a recorded candidate'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '1.0', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0',
                            'com.probe:unstable-ceiling:1.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true, null,
      { current -> current.metadata != null } as ComponentFilter)

    then: 'the predicate applied the record rather than the candidate, so the ceiling stands'
    applied[0].latestVersion == '3.0-Beta1'
    applied[0].unresolved == null
  }

  def 'A build already on a pre-release is still offered a newer one'() {
    given: 'the declared version is itself a pre-release'
    def statuses = [statusOf('com.probe', 'unstable-ceiling', '3.0-Beta1', '3.0-Beta1')]
    def candidates = [':': ['com.probe:unstable-ceiling:3.0-Beta1', 'com.probe:unstable-ceiling:2.0']]

    when:
    def applied = applyRules(statuses, candidates, null, 'milestone', true)

    then: 'the check reads both versions, so nothing is held back'
    applied[0].latestVersion == '3.0-Beta1'
  }

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
  def 'A row moved from #rejected to #moved with the project url #url is reported with #reported'() {
    given:
    def status = statusOf('com.example', 'widget', '0.1', rejected, ':', url)
    def candidates = [':': ["com.example:widget:${rejected}".toString(), "com.example:widget:${moved}".toString()]]

    when:
    def applied = applyRules([status], candidates, rejecting(rejected))

    then:
    applied[0].latestVersion == moved
    applied[0].projectUrl == reported

    where:
    rejected    | moved     | url                                       | reported
    '1.7.0'     | '1.6.2'   | 'https://e.com/releases#1.7.0'            | 'https://e.com/releases#1.6.2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0/index.html'     | 'https://e.com/docs/1.6.2/index.html'
    '1.7.0'     | '1.6.2'   | 'https://github.com/o/r/tree/v1.7.0'      | 'https://github.com/o/r/tree/v1.6.2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/1.7.0/a/1.7.0'             | 'https://e.com/1.6.2/a/1.6.2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/releases#1.7'              | 'https://e.com/releases'
    '1.7.0'     | '1.6.2'   | 'https://e.com/releases#1_7_0'            | 'https://e.com/releases'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0/#1_7_0'         | 'https://e.com/docs/1.6.2/'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0/#1.7.0'         | 'https://e.com/docs/1.6.2/#1.6.2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/releases#1.7.0/1_7_0'      | 'https://e.com/releases#1.7.0/1_7_0'
    '1.7.0'     | '1.6.2'   | 'https://e.com/#docs/1.7/1.7.0/guide'     | 'https://e.com/#docs/1.7/1.7.0/guide'
    '1.7.0'     | '1.6.2'   | 'https://e.com/#docs/1.7.0/guide'         | 'https://e.com/#docs/1.6.2/guide'
    '1.7.0'     | '1.6.2'   | 'https://e.com/releases#1.7.1'            | 'https://e.com/releases'
    '1.7.0'     | '1.6.2'   | 'https://e.com/releases#release-1.7'      | 'https://e.com/releases'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/#/1.7.0/widget2'      | 'https://e.com/docs/#/1.6.2/widget2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs?v=1.7.0#notes'        | 'https://e.com/docs?v=1.7.0#notes'
    '1.7.0'     | '1.6.2'   | 'https://e.com/guide#section-2'           | 'https://e.com/guide#section-2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/guide#notes'               | 'https://e.com/guide#notes'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/#/components/widget2' | 'https://e.com/docs/#/components/widget2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/#!/v1.7/widget2'      | 'https://e.com/docs/#!/v1.7/widget2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0.1/'             | 'https://e.com/docs/1.7.0.1/'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7/index.html'       | 'https://e.com/docs/1.7/index.html'
    '1.7.0'     | '1.6.2'   | 'https://e.com/5.x-1.7/1.7.0/'            | 'https://e.com/5.x-1.7/1.7.0/'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7/1.7.0/#1.7.0'     | 'https://e.com/docs/1.7/1.7.0/#1.7.0'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0-rc1/'           | 'https://e.com/docs/1.7.0-rc1/'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0_1/'             | 'https://e.com/docs/1.7.0_1/'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0+b1/'            | 'https://e.com/docs/1.7.0+b1/'
    '1.7.0'     | '1.6.2'   | 'https://e.com/releases#1.7.0-rc1'        | 'https://e.com/releases'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/1.7.0.html'           | 'https://e.com/docs/1.6.2.html'
    '1.7.0'     | '1.6.2'   | 'https://e.com/docs/#/1.7.0/1.7/x'        | 'https://e.com/docs/#/1.7.0/1.7/x'
    '1.7.0'     | 'v1.6.2'  | 'https://github.com/o/r/tree/v1.7.0'      | 'https://github.com/o/r/tree/v1.6.2'
    '1.7.0'     | '1.6.2'   | 'https://e.com/plain'                     | 'https://e.com/plain'
    '1.7.0'     | '1.6.2'   | 'https://1.7.0.e.com:1700/plain'          | 'https://1.7.0.e.com:1700/plain'
    '1.7.0'     | '1.6.2'   | '//release-1.7.0.org/docs'                | '//release-1.7.0.org/docs'
    '1.7.0'     | '1.6.2'   | 'scm:git:https://e.com/r.git#1.7.0'       | 'scm:git:https://e.com/r.git#1.7.0'
    '1.7.0'     | '1.6.2'   | null                                      | null
    '1.7.0'     | '1.6.2'   | ''                                        | ''
    '5'         | '4'       | 'https://junit.org/junit5/'               | 'https://junit.org/junit5/'
    '5'         | '4'       | 'https://release-5.org/docs'              | 'https://release-5.org/docs'
    '5'         | '4'       | 'https://e.com/docs/5_1/'                 | 'https://e.com/docs/5_1/'
    '5'         | '4'       | 'https://e.com/docs/1-5/'                 | 'https://e.com/docs/1-5/'
    '5'         | '4'       | 'https://e.com/docs/1+5/'                 | 'https://e.com/docs/1+5/'
    '1.7.0'     | '1.6.2'   | 'https://e.com/tags/1.7.0%2Bbuild/'       | 'https://e.com/tags/1.7.0%2Bbuild/'
    '5'         | '4'       | 'https://e.com/releases#5_1'              | 'https://e.com/releases'
    '5'         | '4'       | 'https://e.com/release-5/v5/'             | 'https://e.com/release-4/v4/'
    '1.0'       | '0.9'     | 'https://e.com/v11.0/'                    | 'https://e.com/v11.0/'
    '1.0+build' | '0.9'     | 'https://e.com/1.0+build/'                | 'https://e.com/0.9/'
    '1.0+build' | '0.9'     | 'https://e.com/1.0xbuild/'                | 'https://e.com/1.0xbuild/'
    '2.0'       | '1.0$1'   | 'HTTPS://e.com/2.0/'                      | 'HTTPS://e.com/1.0$1/'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
  def 'The project url is kept on a row that stays at its verdict when only its pre-release step changes'() {
    given: 'the step above the verdict is rejected, but the verdict itself is accepted'
    def status = new PartialStatus('com.example', 'widget', '1.0', null, '3.0', 'https://example.com#1.2', null, false,
      [], ':', [], [], false, null, [], false, '4.0-rc1')
    def candidates = [':': ['com.example:widget:3.0', 'com.example:widget:2.0']]

    when:
    def applied = applyRules([status], candidates, rejecting('4.0-rc1'))

    then:
    applied[0].latestVersion == '3.0'
    applied[0].preReleaseVersion == null
    applied[0].projectUrl == 'https://example.com#1.2'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
  def 'Each project url is kept on rows that stay at one verdict'() {
    given: 'two repositories serve the verdict with different urls'
    def first = statusOf('com.example', 'widget', '1.0', '3.0', ':app', 'https://a.example')
    def second = statusOf('com.example', 'widget', '1.0', '3.0', ':lib', 'https://b.example')
    def candidates = [':app': ['com.example:widget:3.0'], ':lib': ['com.example:widget:3.0']]

    when:
    def applied = applyRules([first, second], candidates, rejecting('2.0'))

    then:
    applied*.projectUrl == ['https://a.example', 'https://b.example']
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
  def 'The project url of the rejected version is kept on a row with every candidate rejected'() {
    given:
    def status = statusOf('com.example', 'widget', '1.0', '3.0', ':', 'https://example.com#3.0')
    def candidates = [':': ['com.example:widget:3.0']]

    when:
    def applied = applyRules([status], candidates, rejecting('3.0'))

    then:
    applied[0].unresolved != null
    applied[0].projectUrl == 'https://example.com#3.0'
  }

  @Unroll
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
  def 'A moved row is reported with the project url read for its version over a rewrite, reversed: #reversed'() {
    given: 'another status resolved the version the row moves to, from a POM with a different url'
    def moved = statusOf('com.example', 'widget', '1.0', '3.0', ':app', own)
    def other = statusOf('com.example', 'widget', '1.5', '2.0', ':lib', 'https://archive.example')
    def candidates = [':app': ['com.example:widget:3.0', 'com.example:widget:2.0'], ':lib': ['com.example:widget:2.0']]
    def statuses = reversed ? [other, moved] : [moved, other]

    when:
    def applied = applyRules(statuses, candidates, rejecting('3.0'))

    then:
    applied.find { it.declaredVersion == '1.0' }.latestVersion == '2.0'
    applied*.projectUrl == ['https://archive.example', 'https://archive.example']

    where:
    own                       | reversed
    'https://example.com#3.0' | false
    'https://example.com#3.0' | true
    null                      | false
    null                      | true
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
  def 'A moved row is reported with the project url read for its version when that status also moved'() {
    given: 'the rule rejects 2.0 only for the row that resolved it'
    def first = statusOf('com.example', 'widget', '1.0', '3.0', ':app', 'https://example.com#3.0')
    def second = statusOf('com.example', 'widget', '1.5', '2.0', ':lib', 'https://archive.example')
    def candidates = [':app': ['com.example:widget:3.0', 'com.example:widget:2.0'],
                      ':lib': ['com.example:widget:2.0', 'com.example:widget:1.5']]
    def strategy = { ResolutionStrategyWithCurrent rs ->
      rs.componentSelection { rules ->
        rules.all { selection ->
          if (selection.candidate.version == '3.0' ||
            (selection.candidate.version == '2.0' && selection.currentVersion == '1.5')) {
            selection.reject('rejected by the test rule')
          }
        }
      }
    } as Action<ResolutionStrategyWithCurrent>

    when:
    def applied = applyRules([first, second], candidates, strategy)

    then:
    applied*.latestVersion == ['2.0', '1.5']
    applied[0].projectUrl == 'https://archive.example'
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/844')
  def 'The url of a moved row is rewritten where the only project url read for its version is empty'() {
    given:
    def moved = statusOf('com.example', 'widget', '1.0', '3.0', ':app', 'https://example.com#3.0')
    def empty = statusOf('com.example', 'widget', '1.5', '2.0', ':lib', '')
    def candidates = [':app': ['com.example:widget:3.0', 'com.example:widget:2.0'], ':lib': ['com.example:widget:2.0']]

    when:
    def applied = applyRules([moved, empty], candidates, rejecting('3.0'))

    then:
    applied[0].projectUrl == 'https://example.com#2.0'
    applied[1].projectUrl == ''
  }
}
