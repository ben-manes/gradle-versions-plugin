[![Build](https://github.com/ben-manes/gradle-versions-plugin/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/ben-manes/gradle-versions-plugin/actions/workflows/build.yml)
[![gradlePluginPortal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/io/github/ben-manes/versions/io.github.ben-manes.versions.gradle.plugin/maven-metadata.xml.svg?label=gradlePluginPortal)](https://plugins.gradle.org/plugin/io.github.ben-manes.versions)

# Gradle Versions Plugin

This plugin reports which of your build's dependencies, plugins, and Gradle
itself have newer versions available, in the spirit of the [Maven Versions
Plugin](https://www.mojohaus.org/versions-maven-plugin).

**Table of contents**
<!-- TOC -->
- [Getting Started](#getting-started)
  - [Applying the plugin](#applying-the-plugin)
  - [Running the task](#running-the-task)
  - [Configuring the task](#configuring-the-task)
- [The `dependencyUpdates` task](#the-dependencyupdates-task)
  - [Cache invalidation](#cache-invalidation)
  - [Task properties](#task-properties)
    - [A recommended configuration](#a-recommended-configuration)
    - [Every property](#every-property)
    - [Command line options](#command-line-options)
    - [What the report checks](#what-the-report-checks)
      - [Configuration filter](#configuration-filter)
        - [`filterConfigurations`](#filterconfigurations)
        - [`filterDeclaredConfigurations`](#filterdeclaredconfigurations)
        - [Choosing between them](#choosing-between-them)
        - [Kotlin Gradle Plugin](#kotlin-gradle-plugin)
        - [Android Gradle Plugin](#android-gradle-plugin)
      - [Constraints](#constraints)
    - [Which versions it offers](#which-versions-it-offers)
      - [Revisions](#revisions)
      - [Filtering unstable versions](#filtering-unstable-versions)
      - [Respecting declared bounds](#respecting-declared-bounds)
      - [Gradle release channel](#gradle-release-channel)
      - [Gradle versions API base URL](#gradle-versions-api-base-url)
    - [Report output](#report-output)
      - [Optional parameters](#optional-parameters)
        - [`checkForGradleUpdate`](#checkforgradleupdate)
        - [`outputDir`](#outputdir)
        - [`reportfileName`](#reportfilename)
      - [Report format](#report-format)
  - [Multi-project builds](#multi-project-builds)
    - [Shared task settings](#shared-task-settings)
    - [Composite builds](#composite-builds)
    - [Per-project reports](#per-project-reports)
  - [Isolated projects](#isolated-projects)
- [Other ways to apply the plugin](#other-ways-to-apply-the-plugin)
  - [The `plugins` block](#the-plugins-block)
  - [Legacy plugin application](#legacy-plugin-application)
  - [Contributor plugin](#contributor-plugin)
  - [Initialization script](#initialization-script)
- [Samples](#samples)
- [Compatibility](#compatibility)
- [Migrating from prior versions](#migrating-from-prior-versions)
  - [v0.62.0](#v0620)
  - [v0.61.0](#v0610)
  - [v0.60.0](#v0600)
  - [v0.59.0](#v0590)
  - [v0.58.0](#v0580)
  - [v0.57.0](#v0570)
  - [v0.56.0](#v0560)
  - [v0.55.0](#v0550)
  - [v0.54.0 and earlier](#v0540-and-earlier)
- [Related plugins](#related-plugins)
<!-- /TOC -->

## Getting Started

### Applying the plugin

The recommended way to add the Gradle Versions Plugin to any build is to apply
the settings plugin once in the settings script. This approach allows the plugin
to report updates for the plugins and buildscript dependencies that the settings
script declares, in addition to each project's own plugins, buildscript
dependencies, and dependencies. It also automatically covers every
subproject in a multi-project build (see [Multi-project
builds](#multi-project-builds)).

<details open>
<summary>Kotlin</summary>

"settings.gradle.kts":
```kotlin
plugins {
  id("io.github.ben-manes.versions.settings") version "$version"
}
```

</details>

<details>
<summary>Groovy</summary>

"settings.gradle":
```groovy
plugins {
  id 'io.github.ben-manes.versions.settings' version '$version'
}
```

</details>

> [!IMPORTANT]
> Replace `$version` with the current release, shown in the badge at the top of
> this page.

### Running the task

After adding the settings plugin to your build, run the `dependencyUpdates` task
to get the report of up-to-date and outdated dependencies (see [The
dependencyUpdates task](#the-dependencyupdates-task)):

```text
./gradlew dependencyUpdates
```

The report prints to the console and is written to
`build/dependencyUpdates/report.txt`:

```text
------------------------------------------------------------
: Project Dependency Updates (report to plain text file)
------------------------------------------------------------

The following dependencies have later milestone versions:
 - com.google.inject:guice [2.0 -> 7.0.0]
     https://github.com/google/guice
 - org.springframework.boot:spring-boot-dependencies [1.5.8.RELEASE -> 1.5.22.RELEASE -> 4.1.0]
     https://spring.io/projects/spring-boot

Gradle release-candidate updates:
 - Gradle: [8.4 -> 9.7.0]
```

### Configuring the task

The task is configured in the root project's build script:

<details open>
<summary>Kotlin</summary>

"build.gradle.kts":
```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  revision = "release"
  outputFormatter = "json"
}
```

</details>

<details>
<summary>Groovy</summary>

"build.gradle":
```groovy
tasks.named("dependencyUpdates").configure {
  revision = 'release'
  outputFormatter = 'json'
}
```

</details>

A build with no root build script can configure the task from the settings
script instead:

<details open>
<summary>Kotlin</summary>

"settings.gradle.kts":
```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

gradle.rootProject {
  tasks.withType(DependencyUpdatesTask::class.java).configureEach {
    revision = "release"
    outputFormatter = "json"
  }
}
```

</details>

<details>
<summary>Groovy</summary>

"settings.gradle":
```groovy
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

gradle.rootProject {
  tasks.withType(DependencyUpdatesTask).configureEach {
    revision = 'release'
    outputFormatter = 'json'
  }
}
```

</details>

Every task property is covered in [Task properties](#task-properties), which
opens with [a recommended configuration](#a-recommended-configuration) most
builds need: a stability filter so that a pre-release version is not offered
as an update, and a bound so that an upgrade the build has ruled out is not
offered either.

## The `dependencyUpdates` task

Displays a report of the project dependencies that are up-to-date, exceed the
latest version found, have upgrades, or failed to be resolved. When a dependency
cannot be resolved the exception is logged at the `info` level.

The report includes dependencies declared through a version catalog, but the
plugin only reports—it never edits build files or the catalog. See [Related
plugins](#related-plugins) for tools that apply updates automatically.

The report also includes the dependencies that a plugin contributes lazily
rather than the build declaring them, such as the Kotlin standard library and
the tool versions of the `jacoco`, `checkstyle`, and `pmd` plugins. Their
current version is whatever the contributing plugin supplies when the task
runs, so a tool version the build never sets is reported at the default
bundled with Gradle—a version that appears nowhere in the build script. An
extra line is printed under such an entry, so it does not read as a resolution
bug. The line shows the configuration the plugin declared the dependency
against:

```text
 - org.jacoco:org.jacoco.ant [0.8.11 -> 0.8.13]
     contributed by a plugin into the 'jacocoAnt' configuration
```

Set the extension's version, such as `jacoco.toolVersion`, to control what the
report compares against.

A plugin that fills its classpath when it is applied, rather than when the
configuration is first resolved, cannot be told apart from the build declaring
the dependency. Such an entry shows the configuration the dependency was
declared against instead:

```text
 - org.jetbrains.kotlin:kotlin-build-tools-impl [2.4.0 -> 2.4.10]
     declared in the 'kotlinAbiValidationCompatClasspath' configuration
```

A configuration the build declares against directly, such as a tool
configuration of its own, is shown the same way. Reject the name an entry
shows with [`filterDeclaredConfigurations`](#filterdeclaredconfigurations) to
leave it out of the report.

Gradle updates are checked for on the `current`, `release-candidate` and
`nightly` release channels. The plain-text report displays Gradle updates as a
separate category in breadcrumb style, excluding nightly builds. The XML and
JSON reports cover all three release channels: whether a release is an update
with respect to the Gradle instance running the build, whether an update check
failed, and a reason field explaining failures or missing information. The
update check may be disabled using the `checkForGradleUpdate` flag.

### Cache invalidation

To find the latest version of a dependency, the task queries each repository
for the versions available there. Gradle caches the result for 24 hours, so a
version published within the last day may be missing from the report, because
the cached answer predates it. Re-run with `--refresh-dependencies` to bypass
the cache and query the repositories again:

```bash
./gradlew dependencyUpdates --refresh-dependencies
```

> [!TIP]
> The `--refresh-dependencies` flag applies to the whole build rather than to
> this task alone, so it also re-checks every other dependency the build
> resolves and is slower than a normal run. Use it when a release you are
> expecting does not appear, not routinely.

### Task properties

#### A recommended configuration

Most builds start from the same configuration, and it is now one line:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  checkConstraints = true
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  checkConstraints = true
}
```

</details>

- `checkConstraints` adds the versions a `constraints` block manages to the
  report (see [Constraints](#constraints)).

Nothing else is needed. A pre-release candidate is printed as a step of its own
after the newest release rather than in place of it, and `rejectPreReleases`
leaves that step out. A candidate outside a `strictly` or `reject` bound written
in the build, outside a dynamic version declared on the buildscript classpath, or
outside the version fixed by a consumed platform is left out by
`rejectOutOfBounds`, which is on by default (see [Filtering unstable
versions](#filtering-unstable-versions) and [Respecting declared
bounds](#respecting-declared-bounds)).

The [configuration filters](#configuration-filter) are absent only because
their arguments are build-specific: the names to reject come from your own
report.

#### Every property

Each property below configures the report or where it is written, and links to
the section that describes it. A property taking a predicate or a closure has no
command line option, since no command line can express the logic.

| Property | Values | Default | Option |
| --- | --- | --- | --- |
| [`revision`](#revisions) | `release`, `milestone`, `integration` | `milestone` | `--revision` |
| [`gradleReleaseChannel`](#gradle-release-channel) | `current`, `release-candidate`, `nightly` | `release-candidate`, or `current` under `rejectPreReleases` | `--gradle-release-channel` |
| [`checkForGradleUpdate`](#checkforgradleupdate) | `true`, `false` | `true` | `--[no-]check-for-gradle-update` |
| [`checkConstraints`](#constraints) | `true`, `false` | `false` | `--[no-]check-constraints` |
| [`checkBuildEnvironmentConstraints`](#constraints) | `true`, `false` | `false` | `--[no-]check-build-environment-constraints` |
| [`checkEmbeddedKotlin`](#embedded-kotlin) | `true`, `false` | `false` | `--[no-]check-embedded-kotlin` |
| [`filterConfigurations`](#filterconfigurations) | a `Spec<Configuration>` | every configuration | |
| [`filterDeclaredConfigurations`](#filterdeclaredconfigurations) | a `Spec<String>` | every name | |
| [`rejectOutOfBounds`](#respecting-declared-bounds) | `true`, `false` | `true` | `--[no-]reject-out-of-bounds` |
| [`rejectPreReleases`](#filtering-unstable-versions) | `true`, `false` | `false` | `--[no-]reject-pre-releases` |
| [`preReleaseVersionIf`](#filtering-unstable-versions) | a predicate over a version string | nothing added | |
| [`exemptFromBuiltInChecksIf`](#filtering-unstable-versions) | a predicate over the candidate | nothing exempt | |
| [`rejectVersionIf`](#filtering-unstable-versions) | a predicate over the candidate | nothing rejected | |
| [`outputFormatter`](#report-format) | `text`, `json`, `xml`, `html`, `problems`, a comma separated list of those, or a `Reporter` | `text` | `--output-formatter` |
| [`outputDir`](#outputdir) | a directory path | `<buildDirectory>/dependencyUpdates` | `--output-dir` |
| [`reportfileName`](#reportfilename) | a file name, without the extension | `report` | `--report-file-name` |
| [`gradleVersionsApiBaseUrl`](#gradle-versions-api-base-url) | a URL | `https://services.gradle.org/versions/` | `--gradle-versions-api-base-url` |
| [`cleanLegacyPartials`](#v0610) | `true`, `false` | `false` | `--[no-]clean-legacy-partials` |

The [`resolutionStrategy`](#filtering-unstable-versions) block is configured
rather than set to a value, so it is absent from the table above.

#### Command line options

Each option in the table above changes a property for a single run, without an
edit to the build script. Use one to see what a check leaves out, then return to
the configured behavior on the next run:

```bash
./gradlew dependencyUpdates --no-check-constraints
```

`--[no-]` marks the options that take no argument, so
`--no-check-for-gradle-update` turns off a check that is enabled in the build
script. Every option, its description, and the values `--revision` and
`--gradle-release-channel` accept are printed by `gradle help --task
dependencyUpdates`.

Where more than one is set, the command line option is the one that applies,
ahead of a system property and of what is configured in the build.

An option applies within the build it is invoked in, where a system property,
being set for the whole JVM, reaches an included build as well. So where a
report merges an [included build](#composite-builds), an option that governs
what is resolved, `--revision`, `--[no-]check-constraints`,
`--[no-]check-build-environment-constraints` and `--[no-]reject-out-of-bounds`,
changes only the rows resolved by the build it was passed to. The rest apply to
the whole report, the merged rows included (see [Shared task
settings](#shared-task-settings)).

#### What the report checks

##### Configuration filter

Two properties leave things out of the report. `filterConfigurations`
controls which configurations the task checks: a rejected configuration is
never resolved, and nothing reachable only through it is reported.
`filterDeclaredConfigurations` controls which entries stay in the report once
the checks have run, matched against the configuration name printed on the
entry itself.

###### `filterConfigurations`

The task checks every resolvable configuration of every project. A build
that only acts on its main classpaths can restrict the check to them:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  filterConfigurations = Spec<Configuration> {
    it.name == "runtimeClasspath" || it.name == "compileClasspath"
  }
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  filterConfigurations = {
    it.name == "runtimeClasspath" || it.name == "compileClasspath"
  }
}
```

</details>

A dependency is left out of the report once every configuration that reaches
it is rejected, and so is everything reachable only through a rejected
configuration—rejecting `compileClasspath` removes the build's own
dependencies too. Reach for this filter when a whole configuration is noise
and does not need to be resolved at all. Each configuration the filter rejects
is logged at `--info`.

Rejecting a configuration also skips its version lookups. A dependency's list
of versions is fetched once for each repository, however many configurations
reach it, so that fetch is still made while any checked configuration reaches
the dependency.

###### `filterDeclaredConfigurations`

Start from the report line. Rejecting the name an entry shows removes the
entry:

```text
 - org.jacoco:org.jacoco.ant [0.8.11 -> 0.8.13]
     contributed by a plugin into the 'jacocoAnt' configuration
```

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  filterDeclaredConfigurations = Spec<String> { it != "jacocoAnt" }
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  filterDeclaredConfigurations = { it != "jacocoAnt" }
}
```

</details>

It matches exactly the name printed on the entry, from a "contributed by a
plugin into" line or a "declared in" one—a tool configuration the build
declares against directly is rejectable the same way. An entry is left out of
the report when every name it shows is rejected, and an entry printed with no
configuration name is never affected: dependencies declared through
`implementation` and its siblings are printed without one, so no rejection
applies to them, and where such a declaration also backs a named entry, the
rejection removes the attribution line rather than the dependency.

###### Choosing between them

The classpaths a plugin fills for its own tooling, at versions the build never
chose, can be left out with either filter. The [Kotlin Gradle Plugin](#kotlin-gradle-plugin)
and [Android Gradle Plugin](#android-gradle-plugin) sets below are written for
`filterDeclaredConfigurations`, because a rule set on the task that writes the
report is also applied to the entries merged from included builds (see
[Composite builds](#composite-builds)). `filterConfigurations` has to be
declared in each build that resolves, and a rejected configuration is never
resolved. The two differ when the name an entry shows is not one the task
checks (see [The `dependencyUpdates` task](#the-dependencyupdates-task)): a
declarable configuration read through a resolvable classpath that extends it, and
`implementation` with a plugin's contribution in it, both show a name that
`filterConfigurations` cannot match. `filterDeclaredConfigurations` matches
the name shown, with no side effect on what is checked. Buildscript and
settings classpath entries are ordinarily printed with no configuration name,
so neither property affects them; an entry a plugin contributes shows
`classpath` and can be rejected like any other entry.

###### Kotlin Gradle Plugin

The Kotlin Gradle Plugin fills four fixed classpaths for its own tooling, and a
fifth in a project that applies the `signing` plugin. It also fills one
`kotlinCompilerPluginClasspath<SourceSet>` per source set—a [JVM test
suite](https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html)
adds one too—so a list of names goes stale as the build grows. Match the
family by prefix; at KGP 2.4.10:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  filterDeclaredConfigurations = Spec<String> { name ->
    val isKgpInternal = name in setOf(
      "kotlinCompilerClasspath",
      "kotlinBuildToolsApiClasspath",
      "kotlinAbiValidationCompatClasspath",
      "kotlinKlibCommonizerClasspath",
      "kotlinBouncyCastleConfiguration",
    ) || (name.startsWith("kotlinCompilerPluginClasspath") &&
      name != "kotlinCompilerPluginClasspath")
    !isKgpInternal
  }
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  filterDeclaredConfigurations = { String name ->
    def isKgpInternal = name in [
      "kotlinCompilerClasspath",
      "kotlinBuildToolsApiClasspath",
      "kotlinAbiValidationCompatClasspath",
      "kotlinKlibCommonizerClasspath",
      "kotlinBouncyCastleConfiguration",
    ] || (name.startsWith("kotlinCompilerPluginClasspath") &&
      name != "kotlinCompilerPluginClasspath")
    !isKgpInternal
  }
}
```

</details>

The prefix stops short of the unsuffixed `kotlinCompilerPluginClasspath`, and
stays narrower than dropping everything that starts with `kotlin`. A compiler
plugin declared in one of the suffixed classpaths is left out along with them.
Its Gradle plugin's marker is still reported, so a version shared between the
two stays visible, but a compiler plugin versioned apart from its Gradle plugin
is left out with nothing in its place. Declared in the unsuffixed
`kotlinCompilerPluginClasspath`, it stays in the report.

###### Android Gradle Plugin

The Android Gradle Plugin fills a set of its own. At AGP 9.3.1, which builds
in its Kotlin support rather than applying a separate Kotlin plugin, that is
`androidLintTool`, two of the Kotlin classpaths above, and a
`unified-test-platform-*` family thirteen configurations wide. Match that
family by prefix as well: which configurations it contains changes between AGP
releases, and no configuration a build fills itself starts with that prefix.

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  filterDeclaredConfigurations = Spec<String> { name ->
    val isAgpInternal = name in setOf(
      "androidLintTool",
      "kotlinBuildToolsApiClasspath",
      "kotlinCompilerClasspath",
    ) || name.startsWith("unified-test-platform-")
    !isAgpInternal
  }
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  filterDeclaredConfigurations = { String name ->
    def isAgpInternal = name in [
      "androidLintTool",
      "kotlinBuildToolsApiClasspath",
      "kotlinCompilerClasspath",
    ] || name.startsWith("unified-test-platform-")
    !isAgpInternal
  }
}
```

</details>

These names come from the plugins at the versions above, not from Gradle, and
they change between plugin releases. Take the set from your own report
rather than from this page, and keep the matches exact wherever a plugin's
configurations share a prefix with ones the build fills itself.

##### Constraints

If you
[manage](https://docs.gradle.org/current/userguide/dependency_constraints.html)
transitive dependency versions with a `constraints` block, you can enable
checking of constraints by specifying the `checkConstraints` attribute of the
`dependencyUpdates` task. If you want to check external constraints (defined in
init scripts or on a script classpath) you can do so by specifying the
`checkBuildEnvironmentConstraints` attribute of the `dependencyUpdates` task.
The `log4j-core` constraint that Gradle adds to every script classpath, and to
the Scala plugin's `zinc` configuration, is not reported, as it cannot be
changed in the build.

The attribute covers the constraints a project declares, on its own
configurations or on ones they extend. A project applying the
[`java-platform`](https://docs.gradle.org/current/userguide/java_platform_plugin.html)
plugin to define a BOM declares its constraints that way, so running the task on
the platform project reports them.

A project that *consumes* a platform does not declare that platform's
constraints, under any of `platform("group:artifact:version")`,
`enforcedPlatform`, or `platform(project(":platform"))`. Gradle supplies those
versions to the consumer as resolution metadata, which `checkConstraints` does
not read, so they are not enumerated in the consumer's report. This does not
affect the modules the consumer declares itself: a versionless declaration whose
version the platform supplies is reported and offered updates as usual. Only a
module the consumer never declares is absent, and those appear in the platform
project's own report.

The platform a platform project imports is reported here, though. A build whose
platform project declares `api(platform("group:artifact:version"))` reaches that
BOM's constraints, but the BOM itself is declared nowhere the report covers, and
a platform in an included build is further out of reach, since that build is
reported separately (see [Composite builds](#composite-builds)). With
`checkConstraints` an entry for the BOM is printed beside the versionless
declarations whose versions it supplies, so the coordinate to bump appears in
the report. Where the build declares a version for every module, the walk goes
no further than the platform project itself. It also stops at the first
published platform: a BOM imported by that BOM reflects the BOM's version rather
than the build's, and a BOM that arrives as a library's resolution metadata was
never imported by the build, so neither is reported. Every platform project
that imports the BOM is printed on the BOM's entry, by build tree path.

The platform behind a constrained module's version is included in that module's
own entry either way, as `constrained by the platform :platform` for a platform
project included in the build and `constrained by the platform group:artifact`
for a BOM, whose own version appears on its own entry. The platform is only
printed when its constraint is the same version the entry shows. If something
else in the build required a higher version and won out, changing the platform
would not change that version, so nothing is printed. The coordinate to bump
appears on the entry where the version is stated (see
[Respecting declared bounds](#respecting-declared-bounds)). This line does not
depend on `checkConstraints`. The same names are present in the JSON and XML
reports as `constrainedBy`, beside the `platformProjects` importers of a
platform's own entry.

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  checkConstraints = true
  checkBuildEnvironmentConstraints = true
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  checkConstraints = true
  checkBuildEnvironmentConstraints = true
}
```

</details>

##### Embedded Kotlin

In a project that applies `kotlin-dsl` or `embedded-kotlin`, `kotlin-stdlib` and
`kotlin-reflect` are added to the `embeddedKotlin` configuration at the Kotlin
version embedded in Gradle, and `kotlin-scripting-compiler-embeddable` is added
at the same version. Each Gradle release is also paired with one version of the
`kotlin-dsl` plugins, and a later version is published for a later Gradle. Only
a Gradle upgrade changes these versions, and that upgrade is printed on the
Gradle row, so they are left out of the report.

An entry is left out only at the version Gradle sets. A module is still reported
at another version, where it is also declared or constrained in the build at the
embedded version, on a buildscript classpath, and in a project that applies
neither plugin. The `kotlin-dsl` plugins are left out at the paired version on a
buildscript or settings classpath, including with `apply false`, and are
reported where they are declared as a library dependency. The number of entries
left out is printed at the end of the plain text report:

```text
4 entries set by Gradle's embedded Kotlin were left out. Run with --check-embedded-kotlin to see them.
```

Set `checkEmbeddedKotlin` to report them all:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  checkEmbeddedKotlin = true
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  checkEmbeddedKotlin = true
}
```

</details>

The classpaths the Kotlin Gradle Plugin fills for its own tooling are at the
embedded version too, and are still reported. The [Kotlin Gradle
Plugin](#kotlin-gradle-plugin) filter leaves them out.

#### Which versions it offers

##### Revisions

The `revision` task property controls the [Ivy resolution
strategy](https://ant.apache.org/ivy/history/2.4.0/settings/version-matchers.html#Latest%20%28Status%29%20Matcher)
for determining what constitutes the latest version of a dependency. Maven's
dependency metadata does not distinguish between milestone and release versions.
The following strategies are natively supported by Gradle:

* release: selects the latest release
* milestone: select the latest version being either a milestone or a release (default)
* integration: selects the latest revision of the dependency module (such as SNAPSHOT)

The strategy can be specified either on the task or as a system property for ad
hoc usage:

```bash
./gradlew dependencyUpdates -Drevision=release
```

Because Maven repositories do not mark pre-release versions, an alpha or release
candidate reaches the query as the latest version under any revision. What keeps
it from displacing the newest release under `release` and `milestone` is the
version string rather than the revision (see [Filtering unstable
versions](#filtering-unstable-versions)).

##### Latest patch and minor versions

Each row includes the latest patch and the latest minor version ahead of the
latest version overall. The latest patch has the same major and minor parts as
the version in use, and the latest minor version has the same major part:

```
The following dependencies have later milestone versions:
 - com.example:library [1.10.18 -> 1.10.19 -> 1.11.5 -> 2.1.6]
 - com.example:toolkit [2.7.5 -> 2.7.18 -> 3.5.4]
```

A version is printed only where it is later than the one before it, so `2.7.18`
is printed once in the second row, as both the latest patch and the latest minor
version. Gradle splits a version into parts at `.`, `-`, `_` and `+` and between
digits and letters. The major part of `33.7.1-jre` is `33`, and the minor part
is `7`. No patch version is printed for a version with a single numeric part,
such as `5`, and neither is printed for a version that starts with a letter,
such as `r09`, or for a dynamic version such as `1.+`.

Both are resolved as the latest version is, so the revision, the pre-release
check, the declared bound, every `rejectVersionIf` filter and any rule declared on
the configuration apply to them. A version is left out of a row that several
projects share when those projects resolve it differently; a project that found
no version in a tier does not hold the others back.

The JSON and XML reports include them as `available.patch` and
`available.minor`, each present wherever one was found, even where it is the
latest version as well.

##### Filtering unstable versions

A row shows the newest release the resolution accepted, then the newest
pre-release above it as a further step, each printed only where it is newer than
the step before it:

```
The following dependencies have later milestone versions:
 - org.jetbrains.kotlin:kotlin-stdlib [2.4.0 -> 2.4.10 -> 2.4.20-beta1]
 - com.google.guava:guava [15.0 -> 16.0-rc1]
```

The Gradle row is printed the same way, and `rejectPreReleases` governs it
through [`gradleReleaseChannel`](#gradle-release-channel)'s default. Setting
`rejectPreReleases` to `true` leaves the pre-release step out, so the first row
above reads `[2.4.0 -> 2.4.10]`, the second is reported as up to date, and the
Gradle row reports `current` alone.

The pre-release step is the newest candidate that fails the pre-release check
alone: the bound check, the revision and any `rejectVersionIf` filter are
applied to it first, so a candidate one of those rejects is not printed as a
step. That ordering is also why a filter is called for pre-release candidates it
was never called for before. It is left out entirely when the current version is itself a pre-release,
in which case newer pre-releases are the row's accepted version instead.
The markers are `alpha`, `beta`, `canary`, `candidate`, `cr`, `dev`,
`draft`, `ea`, `eap`, `experimental`, `m`, `milestone`, `nightly`, `pr`, `pre`,
`preview`, `rc`, `snap`, `snapshot` and `unstable`, each matched
case-insensitively and only where it stands on its own, along with Maven's
timestamped snapshot form. A marker may also follow a digit directly, as in
`3.2.0rc2`, except `m`, which after a digit is a letter suffix such as
`1.1.1m` rather than a milestone.

`incubating` is not a marker. The Apache Incubator requires it in the version
of a podling's releases, so matching it would leave a release out.

A version ending in a commit hash counts too, since a CI job that publishes on
every commit puts the hash in the version. The hash has to be seven or more hex
characters with at least one `a-f`, so a trailing run of digits stays a build
number. Anything after a `+` is semantic versioning's build metadata and is not
checked.

The check is for a pre-release marker rather than for a stable pattern, so a
version with a qualifier not in the list, such as `10.2.0.jre11`, `1.1.17.SP2`
or `0.4-groovy-1.6`, is passed through rather than hidden.

A candidate is left out by being rejected, so for a module with only
pre-releases published, and the declared version no longer among them, no
candidate is left to resolve. That entry is reported as unresolved, with the
rejected versions listed, rather than as up to date. A `rejectVersionIf` filter
that rejects everything has the same effect.

A convention the markers above do not cover, such as graphql-java's `-nf-`
builds, is added to the check with `preReleaseVersionIf`. A version it matches
is a pre-release wherever the check reads one: it is left out under the same
property and option, a build already on one is still shown a newer one, and
`isPreRelease` in a rule is true for it. The convention is part of the built-in
check, so a version it matches is printed as a step rather than as the row's
accepted version, and `rejectPreReleases` leaves that step out as it does any
other. It is given the version with
any build metadata removed, as the markers are, and it is applied to the version
in use as well as to the candidate. Called more than once on a task, the
predicates accumulate; a subproject that calls it replaces the root's rather
than adding to it (see [Shared task settings](#shared-task-settings)):

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  preReleaseVersionIf { it.contains("-nf-") }
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  preReleaseVersionIf { it.contains('-nf-') }
}
```

</details>

A `rejectVersionIf` filter is applied in addition to the built-in check rather
than in place of it, and it is still applied when the option is passed, so a
convention belongs in `preReleaseVersionIf` rather than in a filter, and an
exception belongs in `exemptFromBuiltInChecksIf`, below. A filter is for a
policy the checks cannot express, such as pinning a module. A candidate is left
out if either rejects it, and neither can restore what the other rejected. A
candidate a filter rejects is not printed as a pre-release step either.

A module can be exempted from both built-in checks, with the checks left on for
the rest of the build. `exemptFromBuiltInChecksIf` takes the same predicate over
the candidate as `rejectVersionIf`; a candidate it matches is not held to
`rejectPreReleases` or to `rejectOutOfBounds`. The exemption is applied
inside the checks, so the two properties and their options apply as they do
without it: `--reject-pre-releases` still leaves the step out for a single run,
and the negative option prints it with the exemption in place. Called
more than once on a task, the predicates accumulate; a subproject that calls it
replaces the root's rather than adding to it (see [Shared task
settings](#shared-task-settings)). Here one module is allowed both its
pre-releases and the versions its declaration bounds out, while every other
module is held to the same two checks:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  exemptFromBuiltInChecksIf { candidate.module == "guava" }
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  exemptFromBuiltInChecksIf { candidate.module == 'guava' }
}
```

</details>

Both built-in checks are readable from a rule, so a policy of your own can
build on them without restating them. `isPreRelease()` is the pre-release check
above for the candidate: true when the candidate is a pre-release, any
convention added with `preReleaseVersionIf` included, and the current version
is not. `isPreRelease(version)` is the version-level test behind it, for a rule
that reads some other version. `isOutOfDeclaredBounds()` is the bound check (see
[Respecting declared bounds](#respecting-declared-bounds)) for the candidate.
A candidate is exempt from both checks, and a negated member keeps one:
`exemptFromBuiltInChecksIf { candidate.module == "guava" && !isOutOfDeclaredBounds() }`
lets guava's pre-releases through and still holds it to its bound. A rule is
applied on every run, so the options do not reach what a rule rejects.

Turn the built-in filter off for a policy of your own, written as a whole in
a component selection rule. There is no agreed standard for what counts as
unstable, but this is a common starting point:

<details open>
<summary>Kotlin</summary>

```kotlin
fun String.isNonStable(): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { uppercase().contains(it) }
  val regex = "^[0-9,.v-]+(-r|-jre|-android)?$".toRegex()
  val isStable = stableKeyword || regex.matches(this)
  return isStable.not()
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
def isNonStable = { String version ->
  def stableKeyword = ['RELEASE', 'FINAL', 'GA'].any { it -> version.toUpperCase().contains(it) }
  def regex = /^[0-9,.v-]+(-r|-jre|-android)?$/
  return !stableKeyword && !(version ==~ regex)
}
```

</details>

The trailing `-jre` and `-android` keep Guava's ordinary releases out of the
unstable set. Note the direction this runs in: it matches the forms a release
may take and hides everything else, so a release with a qualifier the pattern
does not match is left out of the report rather than passed through. Spring's
`.SECnn` security releases go that way, and so do `13.4.0.jre11`, `1.1.31.sec01`
and `9.2-1002-jdbc4`. Check the pattern against the versions in your own build
before relying on it.

In Kotlin the `isNonStable` extension replaces a helper of that name already in
the build. A top-level `fun isNonStable(version: String)` compiles to the same
JVM signature, so keeping both fails the build with a platform declaration
clash.

In a build that merges an included build's entries, a Kotlin rule that calls a
function declared in the build script, as the recipe above does, leaves the
report without a configuration cache entry (see [Composite
builds](#composite-builds)). Move the function into a compiled class there, in
`buildSrc` or an included build. A Groovy build is unaffected.

You can then configure [Component Selection
Rules](https://docs.gradle.org/current/userguide/dynamic_versions.html#sec:component_selection_rules).
The current version of a component can be retrieved with the `currentVersion`
property. You can either use the simplified syntax `rejectVersionIf { ... }` or
configure a complete resolution strategy. Multiple registrations compose, so a
candidate is rejected if any registered filter rejects it.

<details open>
<summary>Kotlin</summary>

<!--  Always modify first examples/kotlin and make sure that it works. THEN modify the README -->

Example 1: reject all non-stable versions

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  rejectVersionIf {
    candidate.version.isNonStable()
  }
}
```

Example 2: disallow release candidates as upgradable versions from stable
versions

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  rejectVersionIf {
    candidate.version.isNonStable() && !currentVersion.isNonStable()
  }
}
```

Example 3: using the full syntax

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  resolutionStrategy {
    componentSelection {
      all {
        if (candidate.version.isNonStable() && !currentVersion.isNonStable()) {
          reject("Release candidate")
        }
      }
    }
  }
}
```

Example 4: disallow candidates less mature than the current version, so an `-rc`
keeps being offered `-rc` updates but never a `-beta`

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  val qualifiers = listOf("preview", "alpha", "beta", "m", "cr", "rc") // order is important
  fun maturityLevel(version: String): Int {
    val index = qualifiers.indexOfFirst {
      version.matches(".*[.\\-]$it[.\\-\\d]*".toRegex(RegexOption.IGNORE_CASE))
    }
    return if (index < 0) qualifiers.size else index
  }

  rejectVersionIf {
    maturityLevel(candidate.version) < maturityLevel(currentVersion)
  }
}
```

</details>

<details>
<summary>Groovy</summary>

<!--  Always modify first examples/groovy and make sure that it works. THEN modify the README -->

Example 1: reject all non-stable versions

```groovy
tasks.named("dependencyUpdates").configure {
  rejectVersionIf {
    isNonStable(candidate.version)
  }
}
```

Example 2: disallow release candidates as upgradable versions from stable
versions

```groovy
tasks.named("dependencyUpdates").configure {
  rejectVersionIf {
    isNonStable(candidate.version) && !isNonStable(currentVersion)
  }
}
```

Example 3: using the full syntax

```groovy
tasks.named("dependencyUpdates").configure {
  resolutionStrategy {
    componentSelection {
      all {
        if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
          reject('Release candidate')
        }
      }
    }
  }
}
```

Example 4: disallow candidates less mature than the current version, so an `-rc`
keeps being offered `-rc` updates but never a `-beta`

```groovy
tasks.named("dependencyUpdates").configure {
  def qualifiers = ['preview', 'alpha', 'beta', 'm', 'cr', 'rc'] // order is important
  def maturityLevel = { String version ->
    def index = qualifiers.findIndexOf { version ==~ /(?i).*[.\-]$it[.\-\d]*/ }
    return (index < 0) ? qualifiers.size() : index
  }

  rejectVersionIf {
    maturityLevel(candidate.version) < maturityLevel(currentVersion)
  }
}
```

</details>

A rule runs a second time as the report is written, so that every row in the
report is checked against it and not only the rows this build resolved.
`metadata` and `getDescriptor` are null there: only the candidate versions each
build found are recorded, not the modules behind them, and the repositories
another build read cannot be queried from this one. A rejection a rule makes
after reading either is ignored at that second pass, and the row stays at the
version the build that resolved it accepted rather than being reported
unresolved. A rule that reads only `candidate`, `currentVersion` or
`versionConstraint` is unaffected.

##### Respecting declared bounds

The report is held to the bounds written in the build. A candidate outside a
bound on the declaration, or outside the version fixed by a consumed platform,
is left out, so the only versions listed are ones the build can actually be
moved to. Set `rejectOutOfBounds` to `false` to turn that off:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  rejectOutOfBounds = false
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  rejectOutOfBounds = false
}
```

</details>

To see what is left out, without an edit to the build script, run
`./gradlew dependencyUpdates --no-reject-out-of-bounds`. Run it to
learn what a bounded module could be moved to if the bound were lifted, such
as whether a fix was released for the module ahead of its platform.

A bound is read the way dependency resolution reads it, so `strictly "[5.3,
6["` admits 5.3.26 and excludes 6.0.1, and `reject "[3.0,)"` excludes
everything from 3.0 up. Only `strictly` and `reject` bound a candidate: a
`require` version is a floor resolution may rise above, a range included, and a
`prefer` version only breaks a tie, so a plain
`implementation("group:name:1.2.3")` is not bounded.

The bound is not applied in two cases where it makes no difference. A
candidate no newer than the version in use is not an upgrade, so it stays, and
a module at a version above everything published is still listed on the
exceeded row. Where the version in use already lies outside the bound, the
bound is not applied at all: once a transitive requirement has pushed the
version past a platform, the platform no longer bounds anything.

The same bound is readable from a rule. The constraint written on a
declaration is available as `versionConstraint`, Gradle's
[`VersionConstraint`](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/VersionConstraint.html).
For a module declared without a version, the constraints fixed for that module
by the consumed platforms are available as `platformVersionConstraints`. It
is a list rather than a single value, because more than one consumed platform
can bound the same module. The query that finds candidates is deliberately
unbounded, so a rule that applies a declared bound reads it from
`versionConstraint` rather than restating it. It is null for a module no
declaration was matched to, such as one a substitution rule resolved to, so
guard for that. A module exempted with `exemptFromBuiltInChecksIf` is not
held to its bound (see [Filtering unstable
versions](#filtering-unstable-versions)), and the verdict the property applies
is readable as `isOutOfDeclaredBounds()`, for a rule of the build's own.
`satisfiesDeclaredBound`, the verdict
a rule applied before the property did, is deprecated and will be removed in a
later release.

The buildscript classpath is the exception. There a dynamic required version
bounds the candidate, so a plugin declared as `version "[1.0, 2["` or
`version "1.+"` is not offered 2.0, whether the version was written in the
`plugins` block or came from a version catalog alias. Gradle flattens an alias to
a bare required version on the marker it synthesizes, leaving the two forms
identical, and a plugin has one declaration, so the interval is the version
declared rather than a floor something else may push past. Where a transitive
requirement on a classpath has pushed the selection past the interval, the
version already resolved lies outside the bound, and the bound is not applied,
as above.

A module declared without a version is additionally bound by the version a
consumed platform sets for it, since the build cannot take that upgrade without
also bumping the platform. Given a platform BOM that pins
`log4j-core` to `2.16.0`:

<details open>
<summary>Kotlin</summary>

```kotlin
dependencies {
  api(platform("org.apache.logging.log4j:log4j-bom:2.16.0"))
  api("org.apache.logging.log4j:log4j-core")
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
dependencies {
  api platform('org.apache.logging.log4j:log4j-bom:2.16.0')
  api 'org.apache.logging.log4j:log4j-core'
}
```

</details>

`log4j-core` is bounded at `2.16.0` even though `versionConstraint` is empty
for it, because the bound comes from the platform instead:

```text
The following dependencies are using the latest milestone version:
 - org.apache.logging.log4j:log4j-core:2.16.0
     constrained by the platform org.apache.logging.log4j:log4j-bom

The following dependencies have later milestone versions:
 - org.apache.logging.log4j:log4j-bom [2.16.0 -> 2.17.0]
```

The platform behind the bound appears on an attribution line under the bounded
entry, so the reason for the version shows up next to it. A platform project
appears as its build tree path, `constrained by the platform :platform`, and a
BOM as its group and module; where several platforms bound the module, all of
them follow `constrained by the platforms`. A constraint written as a range is
left out, since a range alone does not identify which version was selected.

The platform still has an entry of its own, so the upgrade that is actually
available, bumping the BOM, is still printed. A build that centralizes its
platforms in a platform project of its own, including one belonging to an
included build, declares the BOM somewhere this report does not cover. With
`checkConstraints` that BOM is reported here anyway, so the coordinate to bump
is printed either way (see [Constraints](#constraints)). A bound the build
declares on the platform itself applies to that entry too: where a BOM's own
version is `strictly "[2.0, 3.0["`, inline or through a version catalog, the
newest version inside that range is reported and never the major beyond it.

The bound is deliberately narrow:

- Where the build declares a version for a module anywhere in the
  configuration hierarchy, the floor semantics above apply; a platform never
  tightens a version declared directly.
- A platform constraint written as a range admits in-range upgrades, the same
  as a declared `strictly` range.
- A platform constraint written as `prefer` alone does not bound, the same as
  a declared `prefer`.
- When more than one consumed platform bounds the same module, a candidate
  must satisfy every one of them. That is stricter than version resolution
  itself would settle on, so an upgrade is offered only when every consumed
  platform admits it; the version currently resolved is always accepted,
  whichever platform supplied it.
- Only a platform bounds. A constraint in an ordinary library's module
  metadata supplies a version without bounding the report.
- Gradle turns an `enforcedPlatform`'s own version into a `strictly`, so the
  rule bounds the platform itself at that version and a newer BOM stops being
  offered; a plain `platform` keeps its own upgrade line.

Three things to know before writing a rule against a declared bound. Rejecting
every candidate for a module, including the version it currently resolves to,
does not fail the build: the module is reported in the unresolved section
instead, and its upgrade line with it. A bound on a version that was never
published has that effect, so a `strictly "1.2.3"` matching nothing is reported
as a resolution failure rather than as up to date.

A `resolutionStrategy` that throws while the plugin applies it to a
configuration, rather than while Gradle resolves the graph, skips that
configuration instead: its dependencies are absent from every section, the
skipped configuration is listed in the report's `skipped` section along with
the failure, and a warning is printed to the console. The build still succeeds.

Narrowing the candidate set can also surface metadata that the unbounded query
skipped over, with the same result. And where a module's only declaration is a
constraint, that constraint is reported as its current version, so
`currentVersion` may read `[2.0, 3.1[` rather than a resolved version.

The declared `strictVersion`, `requiredVersion`, `preferredVersion` and
`rejectedVersions` remain available on `versionConstraint` for rules that need
something other than the bound.

##### Gradle release channel

The `gradleReleaseChannel` task property controls which release channel of the
Gradle project is used to check for available Gradle updates. Options are:

* `current`
* `release-candidate`
* `nightly`

The default follows [`rejectPreReleases`](#filtering-unstable-versions), so one
setting answers for the Gradle row and the dependency rows alike: with the
pre-release step printed, which it is by default, the default here is
`release-candidate`, and a build that sets `rejectPreReleases = true` reports
`current` alone. Stating the property, passing the option, or setting the system
property is read ahead of that, so a build that leaves out every dependency's
pre-release step and still wants the Gradle release candidate can say so. The
value can be changed as shown below:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  gradleReleaseChannel = "current"
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  gradleReleaseChannel = "current"
}
```

</details>

##### Gradle versions API base URL

The `gradleVersionsApiBaseUrl` task property provides an option for
customization of the Gradle versions service URL. If not specified, the default
value https://services.gradle.org/versions/ is used. The customization can be
useful in restricted environments without direct internet access and proxy
availability.

#### Report output

##### Optional parameters

The `dependencyUpdates` task takes several optional parameters to adjust its
behavior. For an ad hoc run, change one with its [command line
option](#command-line-options) (e.g. `--revision release`). The `revision`,
`gradleReleaseChannel`, `outputFormatter`, `outputDir`, and `reportfileName`
properties may also be set as system properties (e.g. `-Drevision=release`),
which predate the options and are kept for the builds that use them; where both
are set, the option is the one that applies:

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  checkForGradleUpdate = true
  outputFormatter = "json"
  outputDir = "build/dependencyUpdates"
  reportfileName = "report"
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  checkForGradleUpdate = true
  outputFormatter = "json"
  outputDir = "build/dependencyUpdates"
  reportfileName = "report"
}
```

</details>

###### `checkForGradleUpdate`

Whether the report includes the Gradle releases the build could upgrade to,
which are read from the [Gradle versions
API](#gradle-versions-api-base-url) over the network for each
[release channel](#gradle-release-channel) consulted. Turning it off skips those
requests, which suits a build that runs offline or in a restricted environment:

```bash
./gradlew dependencyUpdates --no-check-for-gradle-update
```

###### `outputDir`

The directory the report file is written into. A relative path is resolved
against the project directory and an absolute one is used as given. The default
is `dependencyUpdates` under the project's build directory, which is
`build/dependencyUpdates` in a standard layout.

###### `reportfileName`

The report file's name, without an extension. Each formatter supplies its own,
so `report` becomes `report.txt`, `report.json`, `report.xml` or `report.html`.
Naming more than one file format writes one file per format, all sharing this
name.

##### Report format

The task property `outputFormatter` controls the report output format. The
following values are supported:

* `"plain"`: format output file as plain text (default)
* `"json"`: format output file as json text
* `"xml"`: format output file as xml text, can be used by other plugins (e.g. sonar)
* `"html"`: format output file as html
* `"problems"`: report each outdated dependency to Gradle's
  [Problems API](https://docs.gradle.org/current/userguide/reporting_problems.html) rather than to a file
* `Closure`: will be called with the result of the dependency update analysis
  (from Kotlin, use the `outputFormatter(Action<Result>)` function instead)

The `problems` format adds each outdated dependency to Gradle's problems report,
`build/reports/problems/problems-report.html`. Each problem is labeled with the
versions printed in its row of the plain text report, and an upgrade to each of
those later versions is listed as a solution. The lines printed under the row are
included as the problem's details: the `because` reason, the project URL, and
where the dependency comes from. In a report of more than one project, the
projects that declare the dependency are always listed there, not only where
their versions differ. The problems are printed on the console under
`--warning-mode all`. The Problems API is incubating and needs Gradle 8.13 or
later, so on an older Gradle the format is skipped with a message at the info log
level.

The console summary is printed at the lifecycle log level, so `--quiet` suppresses
it. A file format's report is still written; read it, or drop `--quiet`, if a
script was piping the console output.

You can also set multiple output formats using comma as the separator:

```bash
./gradlew dependencyUpdates -Drevision=release -DoutputFormatter=json,xml,html
```

The task property `outputDir` controls the output directory for the report
file(s). The directory will be created if it does not exist. The default value
is set to `build/dependencyUpdates`

```bash
./gradlew dependencyUpdates -Drevision=release -DoutputFormatter=json -DoutputDir=/any/path/with/permission
```

Last the property `reportfileName` sets the filename (without extension) of the
generated report. It defaults to `report`. The extension will be set according
to the used output format.

```bash
./gradlew dependencyUpdates -Drevision=release -DoutputFormatter=json -DreportfileName=myCustomReport
```

Sample output in each format:

<details open>
<summary>Text report</summary>

```
------------------------------------------------------------
: Project Dependency Updates (report to plain text file)
------------------------------------------------------------

The following dependencies are using the latest milestone version:
 - backport-util-concurrent:backport-util-concurrent:3.1
 - backport-util-concurrent:backport-util-concurrent-java12:3.1
 - io.github.ben-manes:gradle-versions-plugin:0.55.0

The following dependencies exceed the version found at the milestone revision level:
 - com.google.guava:guava-tests [99.0-SNAPSHOT <- 23.3-jre]
     https://github.com/google/guava

The following dependencies have later milestone versions:
 - com.google.guava:guava [15.0 -> 23.0]
     https://github.com/google/guava
 - com.google.inject:guice [2.0 -> 7.0.0]
     https://github.com/google/guice
 - com.google.inject.extensions:guice-multibindings [2.0 -> 4.2.3]
     https://github.com/google/guice
 - com.linecorp.armeria:armeria [0.90.0 -> 0.90.3 -> 0.99.9 -> 1.40.0]
     https://armeria.dev/
 - io.zipkin.brave:brave [5.7.0 -> 5.18.1 -> 6.3.1]
     https://github.com/openzipkin/brave/brave
 - org.springframework.boot:spring-boot-dependencies [1.5.8.RELEASE -> 1.5.22.RELEASE -> 4.1.0]
     https://spring.io/projects/spring-boot

Failed to compare versions for the following dependencies because they were declared without version:
 - com.google.code.gson:gson

Failed to determine the latest version for the following dependencies (use --info for details):
 - com.github.ben-manes:unresolvable:1.0
     Could not find any matches for com.github.ben-manes:unresolvable:+ as no versions of com.github.ben-manes:unresolvable are available.
 - com.github.ben-manes:unresolvable2:1.0
     Could not find any matches for com.github.ben-manes:unresolvable2:+ as no versions of com.github.ben-manes:unresolvable2 are available.
 - com.google.guava:guava:15.0
     https://github.com/google/guava
 - dom4j:dom4j

Gradle release-candidate updates:
 - Gradle: [8.4 -> 9.7.0]
```

</details>

Alternatively, the report may be output to a structured file.

<details>
<summary>JSON report</summary>

```json
{
 "count": 15,
 "current": {
  "count": 3,
  "dependencies": [
   {
    "group": "backport-util-concurrent",
    "name": "backport-util-concurrent",
    "version": "3.1",
    "projectUrl": "http://backport-jsr166.sourceforge.net/",
    "userReason": null
   },
   {
    "group": "backport-util-concurrent",
    "name": "backport-util-concurrent-java12",
    "version": "3.1",
    "projectUrl": "http://backport-jsr166.sourceforge.net/",
    "userReason": null
   },
   {
    "group": "io.github.ben-manes",
    "name": "gradle-versions-plugin",
    "version": "0.55.0",
    "projectUrl": null,
    "userReason": null
   }
  ]
 },
 "outdated": {
  "count": 6,
  "dependencies": [
   {
    "group": "com.google.guava",
    "name": "guava",
    "version": "15.0",
    "projectUrl": "https://github.com/google/guava",
    "userReason": null,
    "available": {
     "release": null,
     "milestone": "23.0",
     "integration": null,
     "preRelease": null,
     "patch": null,
     "minor": null
    }
   },
   {
    "group": "com.google.inject",
    "name": "guice",
    "version": "2.0",
    "projectUrl": "https://github.com/google/guice",
    "userReason": null,
    "available": {
     "release": null,
     "milestone": "7.0.0",
     "integration": null,
     "preRelease": null,
     "patch": null,
     "minor": null
    }
   },
   {
    "group": "com.google.inject.extensions",
    "name": "guice-multibindings",
    "version": "2.0",
    "projectUrl": "https://github.com/google/guice",
    "userReason": null,
    "available": {
     "release": null,
     "milestone": "4.2.3",
     "integration": null,
     "preRelease": null,
     "patch": null,
     "minor": null
    }
   },
   {
    "group": "com.linecorp.armeria",
    "name": "armeria",
    "version": "0.90.0",
    "projectUrl": "https://armeria.dev/",
    "userReason": null,
    "available": {
     "release": null,
     "milestone": "1.40.0",
     "integration": null,
     "preRelease": null,
     "patch": "0.90.3",
     "minor": "0.99.9"
    }
   },
   {
    "group": "io.zipkin.brave",
    "name": "brave",
    "version": "5.7.0",
    "projectUrl": "https://github.com/openzipkin/brave/brave",
    "userReason": null,
    "available": {
     "release": null,
     "milestone": "6.3.1",
     "integration": null,
     "preRelease": null,
     "patch": null,
     "minor": "5.18.1"
    }
   },
   {
    "group": "org.springframework.boot",
    "name": "spring-boot-dependencies",
    "version": "1.5.8.RELEASE",
    "projectUrl": "https://spring.io/projects/spring-boot",
    "userReason": null,
    "available": {
     "release": null,
     "milestone": "4.1.0",
     "integration": null,
     "preRelease": null,
     "patch": "1.5.22.RELEASE",
     "minor": "1.5.22.RELEASE"
    }
   }
  ]
 },
 "exceeded": {
  "count": 1,
  "dependencies": [
   {
    "group": "com.google.guava",
    "name": "guava-tests",
    "version": "99.0-SNAPSHOT",
    "projectUrl": "https://github.com/google/guava",
    "userReason": null,
    "latest": "23.3-jre"
   }
  ]
 },
 "undeclared": {
  "count": 1,
  "dependencies": [
   {
    "group": "com.google.code.gson",
    "name": "gson",
    "version": null,
    "projectUrl": null,
    "userReason": null
   }
  ]
 },
 "unresolved": {
  "count": 4,
  "dependencies": [
   {
    "group": "com.github.ben-manes",
    "name": "unresolvable",
    "version": "1.0",
    "projectUrl": null,
    "userReason": null,
    "reason": "Could not find any matches for com.github.ben-manes:unresolvable:+ as no versions of com.github.ben-manes:unresolvable are available.\nSearched in the following locations:\n  - https://repo.maven.apache.org/maven2/com/github/ben-manes/unresolvable/maven-metadata.xml"
   },
   {
    "group": "com.github.ben-manes",
    "name": "unresolvable2",
    "version": "1.0",
    "projectUrl": null,
    "userReason": null,
    "reason": "Could not find any matches for com.github.ben-manes:unresolvable2:+ as no versions of com.github.ben-manes:unresolvable2 are available.\nSearched in the following locations:\n  - https://repo.maven.apache.org/maven2/com/github/ben-manes/unresolvable2/maven-metadata.xml"
   },
   {
    "group": "com.google.guava",
    "name": "guava",
    "version": "15.0",
    "projectUrl": "https://github.com/google/guava",
    "userReason": null,
    "reason": "Could not resolve com.google.guava:guava:+."
   },
   {
    "group": "dom4j",
    "name": "dom4j",
    "version": "none",
    "projectUrl": null,
    "userReason": null,
    "reason": "Could not resolve dom4j:dom4j:+."
   }
  ]
 },
 "gradle": {
  "enabled": true,
  "running": {
   "isFailure": false,
   "isUpdateAvailable": false,
   "reason": "",
   "version": "8.4"
  },
  "current": {
   "isFailure": false,
   "isUpdateAvailable": true,
   "reason": "",
   "version": "9.7.0"
  },
  "releaseCandidate": {
   "isFailure": false,
   "isUpdateAvailable": false,
   "reason": "update check succeeded: no release available",
   "version": ""
  },
  "nightly": {
   "isFailure": false,
   "isUpdateAvailable": false,
   "reason": "update check disabled",
   "version": ""
  }
 },
 "skipped": {
  "count": 1,
  "configurations": [
   {
    "project": ":",
    "name": "compileClasspath",
    "reason": "org.gradle.api.InvalidUserCodeException: Could not add a component selection rule for module 'com.google.guava'."
   }
  ]
 }
}
```

</details>

<details>
<summary>XML report</summary>

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<response>
    <count>15</count>
    <current>
        <count>3</count>
        <dependencies>
            <dependency>
                <group>backport-util-concurrent</group>
                <name>backport-util-concurrent</name>
                <version>3.1</version>
                <projectUrl>http://backport-jsr166.sourceforge.net/</projectUrl>
            </dependency>
            <dependency>
                <group>backport-util-concurrent</group>
                <name>backport-util-concurrent-java12</name>
                <version>3.1</version>
                <projectUrl>http://backport-jsr166.sourceforge.net/</projectUrl>
            </dependency>
            <dependency>
                <group>io.github.ben-manes</group>
                <name>gradle-versions-plugin</name>
                <version>0.55.0</version>
            </dependency>
        </dependencies>
    </current>
    <outdated>
        <count>6</count>
        <dependencies>
            <outdatedDependency>
                <group>com.google.guava</group>
                <name>guava</name>
                <version>15.0</version>
                <projectUrl>https://github.com/google/guava</projectUrl>
                <available>
                    <milestone>23.0</milestone>
                </available>
            </outdatedDependency>
            <outdatedDependency>
                <group>com.google.inject</group>
                <name>guice</name>
                <version>2.0</version>
                <projectUrl>https://github.com/google/guice</projectUrl>
                <available>
                    <milestone>7.0.0</milestone>
                </available>
            </outdatedDependency>
            <outdatedDependency>
                <group>com.google.inject.extensions</group>
                <name>guice-multibindings</name>
                <version>2.0</version>
                <projectUrl>https://github.com/google/guice</projectUrl>
                <available>
                    <milestone>4.2.3</milestone>
                </available>
            </outdatedDependency>
            <outdatedDependency>
                <group>com.linecorp.armeria</group>
                <name>armeria</name>
                <version>0.90.0</version>
                <projectUrl>https://armeria.dev/</projectUrl>
                <available>
                    <milestone>1.40.0</milestone>
                    <patch>0.90.3</patch>
                    <minor>0.99.9</minor>
                </available>
            </outdatedDependency>
            <outdatedDependency>
                <group>io.zipkin.brave</group>
                <name>brave</name>
                <version>5.7.0</version>
                <projectUrl>https://github.com/openzipkin/brave/brave</projectUrl>
                <available>
                    <milestone>6.3.1</milestone>
                    <minor>5.18.1</minor>
                </available>
            </outdatedDependency>
            <outdatedDependency>
                <group>org.springframework.boot</group>
                <name>spring-boot-dependencies</name>
                <version>1.5.8.RELEASE</version>
                <projectUrl>https://spring.io/projects/spring-boot</projectUrl>
                <available>
                    <milestone>4.1.0</milestone>
                    <patch>1.5.22.RELEASE</patch>
                    <minor>1.5.22.RELEASE</minor>
                </available>
            </outdatedDependency>
        </dependencies>
    </outdated>
    <exceeded>
        <count>1</count>
        <dependencies>
            <exceededDependency>
                <group>com.google.guava</group>
                <name>guava-tests</name>
                <version>99.0-SNAPSHOT</version>
                <projectUrl>https://github.com/google/guava</projectUrl>
                <latest>23.3-jre</latest>
            </exceededDependency>
        </dependencies>
    </exceeded>
    <undeclared>
        <count>1</count>
        <dependencies>
            <dependency>
                <group>com.google.code.gson</group>
                <name>gson</name>
            </dependency>
        </dependencies>
    </undeclared>
    <unresolved>
        <count>4</count>
        <dependencies>
            <unresolvedDependency>
                <group>com.github.ben-manes</group>
                <name>unresolvable</name>
                <version>1.0</version>
                <reason>Could not find any matches for com.github.ben-manes:unresolvable:+ as no versions of com.github.ben-manes:unresolvable are available.
Searched in the following locations:
  - https://repo.maven.apache.org/maven2/com/github/ben-manes/unresolvable/maven-metadata.xml</reason>
            </unresolvedDependency>
            <unresolvedDependency>
                <group>com.github.ben-manes</group>
                <name>unresolvable2</name>
                <version>1.0</version>
                <reason>Could not find any matches for com.github.ben-manes:unresolvable2:+ as no versions of com.github.ben-manes:unresolvable2 are available.
Searched in the following locations:
  - https://repo.maven.apache.org/maven2/com/github/ben-manes/unresolvable2/maven-metadata.xml</reason>
            </unresolvedDependency>
            <unresolvedDependency>
                <group>com.google.guava</group>
                <name>guava</name>
                <version>15.0</version>
                <projectUrl>https://github.com/google/guava</projectUrl>
                <reason>Could not resolve com.google.guava:guava:+.</reason>
            </unresolvedDependency>
            <unresolvedDependency>
                <group>dom4j</group>
                <name>dom4j</name>
                <version>none</version>
                <reason>Could not resolve dom4j:dom4j:+.</reason>
            </unresolvedDependency>
        </dependencies>
    </unresolved>
    <skipped>
        <count>1</count>
        <configurations>
            <skippedConfiguration>
                <project>:</project>
                <name>compileClasspath</name>
                <reason>org.gradle.api.InvalidUserCodeException: Could not add a component selection rule for module 'com.google.guava'.</reason>
            </skippedConfiguration>
        </configurations>
    </skipped>
    <gradle>
        <enabled>true</enabled>
        <running>
            <version>8.4</version>
            <isUpdateAvailable>false</isUpdateAvailable>
            <isFailure>false</isFailure>
            <reason/>
        </running>
        <current>
            <version>9.7.0</version>
            <isUpdateAvailable>true</isUpdateAvailable>
            <isFailure>false</isFailure>
            <reason/>
        </current>
        <releaseCandidate>
            <version/>
            <isUpdateAvailable>false</isUpdateAvailable>
            <isFailure>false</isFailure>
            <reason>update check succeeded: no release available</reason>
        </releaseCandidate>
        <nightly>
            <version/>
            <isUpdateAvailable>false</isUpdateAvailable>
            <isFailure>false</isFailure>
            <reason>update check disabled</reason>
        </nightly>
    </gradle>
</response>
```

</details>

<details>
<summary>HTML report</summary>

[<img src="examples/html-report.png"/>](examples/html-report.png)

</details>

<details>
<summary>Custom report</summary>

If you need to create a report in a custom format, you can provide a formatter
function to the `dependencyUpdates` task's `outputFormatter`. The formatter
receives the analysis as an instance of
[com.github.benmanes.gradle.versions.reporter.result.Result](gradle-versions-plugin/src/main/kotlin/com/github/benmanes/gradle/versions/reporter/result/Result.kt):
in the Kotlin DSL it is the receiver of the formatter block, and in Groovy it is
passed as the closure argument.

> [!IMPORTANT]
> Under the configuration cache, the formatter cannot reach the project or the
> build script from inside the closure, because it runs at execution time. Read
> what it needs into local variables beforehand, as shown in
> [Migrating from prior versions](#v0540-and-earlier). Gradle's
> [configuration cache requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html)
> cover the underlying rules.

For example, if you wanted to create an html table for the upgradable
dependencies, you could use the following. Where an entry's only newer candidate
is a pre-release, no version is filled in at the revision level, so the last
fallback is `preRelease` (see [Filtering unstable
versions](#filtering-unstable-versions)):

<details open>
<summary>Kotlin</summary>

```kotlin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
  outputFormatter {
    val updatable = outdated.dependencies
    if (updatable.isNotEmpty()) {
      val table = buildString {
        appendLine("<table>")
        appendLine("  <thead>")
        appendLine("    <tr><td>Group</td><td>Module</td><td>Current version</td><td>Latest version</td></tr>")
        appendLine("  </thead>")
        appendLine("  <tbody>")
        updatable.forEach { dependency ->
          val available = dependency.available
          val latest = available.release ?: available.milestone ?: available.preRelease
          appendLine(
            "    <tr><td>${dependency.group}</td><td>${dependency.name}</td>" +
              "<td>${dependency.version}</td>" +
              "<td>$latest</td></tr>"
          )
        }
        appendLine("  </tbody>")
        appendLine("</table>")
      }
      println(table)
    }
  }
}
```

</details>

<details>
<summary>Groovy</summary>

```groovy
tasks.named("dependencyUpdates").configure {
  outputFormatter = { result ->
    def updatable = result.outdated.dependencies
    if (!updatable.isEmpty()) {
      def table = new StringBuilder()
      table.append("<table>\n")
      table.append("  <thead>\n")
      table.append("    <tr><td>Group</td><td>Module</td><td>Current version</td><td>Latest version</td></tr>\n")
      table.append("  </thead>\n")
      table.append("  <tbody>\n")
      updatable.each { dependency ->
        def available = dependency.available
        def latest = available.release ?: available.milestone ?: available.preRelease
        table.append("    <tr><td>${dependency.group}</td><td>${dependency.name}</td>")
        table.append("<td>${dependency.version}</td>")
        table.append("<td>${latest}</td></tr>\n")
      }
      table.append("  </tbody>\n")
      table.append("</table>")
      println table
    }
  }
}
```

</details>

</details>

### Multi-project builds

Running the task in the root project generates one merged report covering every
project. The report is aggregated from a task in each project, so it works with
parallel execution, the configuration cache, and configure on demand. Under the
configuration cache the dependency metadata read during resolution is tracked as
an input, so a newly published version invalidates the cached entry rather than
serving a stale report.

The merged report and the partial results it merges are written to the
aggregating project's build directory. `clean` removes them only if that project
has a `clean` task, which the `base` plugin supplies. A root project that
applies no other plugin can add `base` for that alone; the task itself does not
need it.

<details open>
<summary>Kotlin</summary>

"build.gradle.kts":
```kotlin
plugins {
  base
}
```

</details>

<details>
<summary>Groovy</summary>

"build.gradle":
```groovy
plugins {
  id 'base'
}
```

</details>

When a coordinate's declared version differs across the aggregated projects,
the projects that declared each version are printed in the plain text, JSON,
XML, and HTML reports, so the entry no longer reads as self-contradictory:

```text
The following dependencies have later milestone versions:
 - org.jacoco:org.jacoco.ant [0.8.14 -> 0.8.15]
     declared in root project
```

One declared version can also have different latest versions across the
projects, which happens when a platform bounds the module in some of them and
not in others. Each of those latest versions is shown on an entry of its own,
with the projects included the same way, rather than the newest of them being
shown for every project:

```text
The following dependencies are using the latest milestone version:
 - com.google.inject:guice:2.0
     constrained by the platform :platform in root project

The following dependencies have later milestone versions:
 - com.google.inject:guice [2.0 -> 7.0.0]
     declared in :platform
```

So a coordinate's group and name can appear on two entries, and in two sections
of one report; the projects printed on each are what distinguish them.

The plain text and HTML reports print the first five projects and a count of the
rest (`declared in :app, :lib, ... and 60 others`). The JSON and XML reports
always include the complete list, so use one of them when a tool needs every
project.

A project is printed as its path in the build tree, so a project of an included
build reads as `:child:app` and that build's root as `:child`. In each build the
root project's own path is `:`, which would otherwise put the projects of two
builds under one name.

With the settings plugin applied (see [Applying the
plugin](#applying-the-plugin)), the `dependencyUpdates` task is registered in
the root project and every other project contributes to it. No project applies
a plugin itself, and a root build script is not required—add one only if you
want to configure the task. The report also covers the plugins the settings
script declares, which appear in no project's buildscript; a version pinned in
`pluginManagement` for a plugin the build never applies is not reported.

The settings plugin registers the root project's `dependencyUpdates` task
before the root build script runs, so a build script that registers its own
task by that name now fails with a duplicate-task error—rename yours.

#### Shared task settings

The settings that control resolution (`revision`, `rejectVersionIf` or a full
`resolutionStrategy`, `filterConfigurations`, `filterDeclaredConfigurations`,
`checkConstraints`, `checkBuildEnvironmentConstraints`,
`rejectOutOfBounds`, `rejectPreReleases`, `preReleaseVersionIf`, and
`exemptFromBuiltInChecksIf`), and `checkEmbeddedKotlin`, are inherited from the
nearest project up the hierarchy whose task set them. Configuring the root
project's task therefore covers every project, unless a subproject configures its own (see [Task
properties](#task-properties)).

An included build merged into the report (see [Composite
builds](#composite-builds)) is covered by most of the same settings, applied at
the report rather than inherited. `rejectVersionIf`, `resolutionStrategy`,
`rejectPreReleases`, `checkEmbeddedKotlin`, `preReleaseVersionIf`,
`exemptFromBuiltInChecksIf` and `filterDeclaredConfigurations` set on, or
inherited by, the task that writes the report are applied to the entries merged
from it, so a composite is configured
in one place, as a multi-project build is. The settings that control what is resolved
are the exception: `revision`, `filterConfigurations`, `checkConstraints`,
`checkBuildEnvironmentConstraints` and `rejectOutOfBounds` are read in the build
that resolves, and are declared in each included build.

#### Composite builds

An included build is a separate build with its own settings script, so its
projects are not part of this build's report. Apply the settings plugin in the
included build's settings script as well, and run its `dependencyUpdates` task
separately. `buildSrc` is a separate build too, and is likewise excluded. This
is not specific to the settings plugin—an included build has never been covered
by default.

To report on every build in one invocation, register a lifecycle task that
depends on each included build's task. Each build writes its own report, which
suits builds that are developed independently, as each keeps its own settings:

<details open>
<summary>Kotlin</summary>

"build.gradle.kts":
```kotlin
tasks.register("allDependencyUpdates") {
  gradle.includedBuilds.forEach { dependsOn(it.task(":dependencyUpdates")) }
}
```

</details>

<details>
<summary>Groovy</summary>

"build.gradle":
```groovy
tasks.register("allDependencyUpdates") {
  gradle.includedBuilds.each { dependsOn(it.task(':dependencyUpdates')) }
}
```

</details>

Every included build needs the plugin applied for its `dependencyUpdates` task
to exist. A build that must stay unmodified can have the plugin injected by an
[init script](#initialization-script) instead. Apply one version of the plugin
across the builds a report spans: a merged report is read from what each build
wrote, and a file written in a format newer than the reading build supports
fails the report, and the error prints the project it came from.

An included build's report can instead be merged into this build's report, by
declaring the build in the `dependencyUpdatesAggregation` configuration of the
project that aggregates. Declare it by the coordinates that the include
substitutes, and apply the plugin in the included build, so that a report exists
to merge. Each declaration merges the project its coordinates resolve to, and
every project that one aggregates, where only that project was merged before. A
build's root project aggregates its whole build, so the root's coordinates merge
all of it, and a subproject's coordinates merge only what that subproject
aggregates. Merging stops at that build's boundary: a build included by the
declared build has to be declared in turn, which reaches it however deeply it is
included by a plain `includeBuild`. A project of this build that the aggregating
project's own tree does not cover, such as a sibling, is declared the same way:

<details open>
<summary>Kotlin</summary>

"build.gradle.kts":
```kotlin
dependencies {
  dependencyUpdatesAggregation("com.example:child:1.0")
}
```

</details>

<details>
<summary>Groovy</summary>

"build.gradle":
```groovy
dependencies {
  dependencyUpdatesAggregation 'com.example:child:1.0'
}
```

</details>

Whether a coordinate is substituted at all rests on its spelling and on two
Gradle rules. The coordinates have to match the `group`, `name` and `version`
set in the included build. A build included only under `pluginManagement` is
not substituted from the including build's dependency graph, so declare a plain
`includeBuild` for it in the aggregating settings as well. An `includeBuild`
that declares a `dependencySubstitution` block keeps only the rules declared in
it, the automatic `group:name` rule included, so a rule for the aggregated
coordinates has to be declared there too. A coordinate substituted onto no
project is left out of the report and none of its entries are merged; it is
printed in a warning rather than failing the build, so an entry left over from
a build that is no longer included does not break the build.

The task that writes the report applies its settings to every entry in it,
including the entries an included build resolved and the entries its subprojects
resolved. Its `rejectVersionIf` and `resolutionStrategy` rules are applied to
those entries, the convention added with `preReleaseVersionIf` and the exception
made with `exemptFromBuiltInChecksIf` are read for them, and
`filterDeclaredConfigurations` leaves out the ones with a configuration name it
rejects. An included build with nothing configured is reported under the
aggregating build's rules. `rejectPreReleases` is settled by the aggregating
task alone, for every entry in the report: an included build's own setting
governs the report that build writes rather than the one its entries are merged
into.

Within one build the pre-release check and a rule behave differently. The
pre-release check reads the convention and the exemption inherited by the
project that resolved the entry, so a subproject with its own
`preReleaseVersionIf` or `exemptFromBuiltInChecksIf` keeps that answer in the
report above it. A `rejectVersionIf` or `resolutionStrategy` rule is policy
applied at the report instead, so the aggregating report's rules reach every
entry, its subprojects' entries included.

The report only narrows what a build reported. The version an entry shows is the
newest one the producing build's resolution accepted, so an aggregating build
can move an entry to an older version and never to a newer one. An included
build with stricter rules caps what the merged report shows for the coordinates
declared in it.

This applies to a subproject as well. Where the project the report is asked for
rejects a version that a subproject accepts, the older version is shown for
every project, and two entries that a per-project rule would have split apart
are shown as one.

An entry the report moved to an older version was never resolved at that
version. The candidates come from a repository listing, so the version satisfied
both builds' rules, and no resolution proved that a usable variant of it exists.
The version accepted in the producing build is the one that build resolved.

The settings that control what is resolved apply in the build that resolves it:
`revision`, `filterConfigurations`, `checkConstraints`,
`checkBuildEnvironmentConstraints` and `rejectOutOfBounds`. Declare those in
each included build. The [Kotlin Gradle Plugin](#kotlin-gradle-plugin) and
[Android Gradle Plugin](#android-gradle-plugin) sets are written for
`filterDeclaredConfigurations` for this reason: a set declared once on the task
that writes the report is applied to the entries merged from every included
build. The Gradle update check is
read from the report being asked for, so `checkForGradleUpdate` and
`gradleReleaseChannel` set in a merged build do not reach it.

A report that applies its rules to another build's entries stores those rules in
the configuration cache. A Kotlin rule that calls a function declared in the
same build script captures the script itself, which the cache cannot store, so
the entry is discarded and a warning prints the project. Declare the rule's
helpers as a compiled class, in `buildSrc` or an included build, to keep the
entry. A helper declared beside the rule in a precompiled script plugin does not
qualify: that script's top level functions are members of it, so the rule
captures that script instead. A Groovy closure is unaffected.

#### Per-project reports

A project can have a `dependencyUpdates` task of its own, reporting on itself
and its subprojects (see [Other ways to apply the
plugin](#other-ways-to-apply-the-plugin)). Run it by its path to get just that
report:

```bash
./gradlew :subproject:dependencyUpdates
```

### Isolated projects

Under [isolated
projects](https://docs.gradle.org/current/userguide/isolated_projects.html) a
project plugin cannot register a task in another project, so a project only
contributes to the aggregate report if it applies a plugin itself. The settings
plugin covers this: it applies a plugin to each project as the project is
evaluated, so the recommended setup works unchanged (see [Applying the
plugin](#applying-the-plugin)).

A build that cannot apply the settings plugin can still cover every project (see
[Contributor plugin](#contributor-plugin)).

Under isolated projects, contributing projects that share a group and name are
aggregated as one, and a warning is printed to the console for any project
missing from the report.

## Other ways to apply the plugin

The settings plugin is the recommended way to apply the plugin (see
[Applying the plugin](#applying-the-plugin)). The options below cover builds that need something
different:

* apply the plugin to a single project—for a separate per-project report,
  alongside or instead of the settings plugin (see [The `plugins`
  block](#the-plugins-block) and [legacy plugin application](#legacy-plugin-application));
* contribute every project to the root report without a settings plugin (see
  [Contributor plugin](#contributor-plugin));
* apply the plugin to every build you run on your machine (see [Initialization
  script](#initialization-script)).

In the snippets below, replace `$version` with the current release, shown in the
badge at the top of this page.

> [!IMPORTANT]
> When the settings plugin is also applied, request the per-project plugin
> *without* a version—the settings plugin already puts it on every project's
> classpath, and a versioned request fails to resolve. This includes a version
> catalog alias, which always includes a version.

### The `plugins` block

<details open>
<summary>Kotlin</summary>

"build.gradle.kts":
```kotlin
plugins {
  id("io.github.ben-manes.versions") version "$version"
}
```

</details>

<details>
<summary>Groovy</summary>

"build.gradle":
```groovy
plugins {
  id "io.github.ben-manes.versions" version "$version"
}
```

</details>

### Legacy plugin application

> [!TIP]
> Prefer the `plugins` block—it is the modern replacement for
> `buildscript`-based plugin application.

<details open>
<summary>Kotlin</summary>

"build.gradle.kts":
```kotlin
buildscript {
  repositories {
    gradlePluginPortal()
  }

  dependencies {
    classpath("io.github.ben-manes:gradle-versions-plugin:$version")
  }
}

apply(plugin = "io.github.ben-manes.versions")
```

</details>

<details>
<summary>Groovy</summary>

"build.gradle":
```groovy
buildscript {
  repositories {
    gradlePluginPortal()
  }

  dependencies {
    classpath "io.github.ben-manes:gradle-versions-plugin:$version"
  }
}

apply plugin: "io.github.ben-manes.versions"
```

</details>

### Contributor plugin

A build that cannot apply the settings plugin—under isolated projects, where a
project plugin cannot register a task in another project (see [Isolated
projects](#isolated-projects))—can keep applying `io.github.ben-manes.versions`
in the root project, and apply `io.github.ben-manes.versions.contributor` in
every other project, typically from a convention plugin they already share:

<details open>
<summary>Kotlin</summary>

"buildSrc/src/main/kotlin/my-conventions.gradle.kts":
```kotlin
plugins {
  id("io.github.ben-manes.versions.contributor")
}
```

</details>

<details>
<summary>Groovy</summary>

"buildSrc/src/main/groovy/my-conventions.gradle":
```groovy
plugins {
  id 'io.github.ben-manes.versions.contributor'
}
```

</details>

The convention plugin's own build must have the plugin on its classpath, e.g. as
an `implementation("io.github.ben-manes:gradle-versions-plugin:$version")`
dependency in `buildSrc/build.gradle.kts`.

The contributor plugin registers only the producer that feeds the aggregate
report, so `dependencyUpdates` remains a single task in the root project. The
main plugin is a superset of the contributor plugin: a project that applies
`io.github.ben-manes.versions` instead still feeds the aggregate report, and
also has a `dependencyUpdates` task of its own, covering itself and its
subprojects.

### Initialization script

You can also transparently add the plugin to every Gradle project that you run
via a
[Gradle init script](https://docs.gradle.org/current/userguide/init_scripts.html).
Apply the settings plugin from `beforeSettings`, which covers every project of
the build and works under isolated projects (see [Isolated
projects](#isolated-projects)). A `dependencyUpdates` task is registered in
every build that runs, so an included build is reported without being modified
(see [Composite builds](#composite-builds)):

<details open>
<summary>Kotlin</summary>

"$HOME/.gradle/init.d/add-versions-plugin.init.gradle.kts":
```kotlin
import com.github.benmanes.gradle.versions.VersionsSettingsPlugin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

initscript {
  repositories {
    gradlePluginPortal()
  }

  dependencies {
    classpath("io.github.ben-manes:gradle-versions-plugin:+")
  }
}

gradle.beforeSettings(Action<Settings> {
  pluginManager.apply(VersionsSettingsPlugin::class.java)
})

gradle.rootProject(Action<Project> {
  tasks.withType(DependencyUpdatesTask::class.java).configureEach {
    // configure the task, for example wrt. resolution strategies
  }
})
```

</details>

<details>
<summary>Groovy</summary>

"$HOME/.gradle/init.d/add-versions-plugin.gradle":
```groovy
import com.github.benmanes.gradle.versions.VersionsSettingsPlugin
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

initscript {
  repositories {
    gradlePluginPortal()
  }

  dependencies {
    classpath 'io.github.ben-manes:gradle-versions-plugin:+'
  }
}

beforeSettings { settings ->
  settings.pluginManager.apply(VersionsSettingsPlugin)
}

gradle.rootProject {
  tasks.withType(DependencyUpdatesTask).configureEach {
    // configure the task, for example wrt. resolution strategies
  }
}
```

</details>

A script has no implicit import for the plugin's types, so the imports at the
top of these snippets are required to reference them by their simple names.

An init script resolves the plugin on a classpath of its own, so a build that
applies the plugin itself ends up with a second copy of it. An init script runs
before the build's own scripts, so its copy is the one that registers the task
and the build's copy does nothing, which leaves such a build working as it did.
For the same reason the plugin is absent from the project's own classpath, so a
`plugins` block that requests it alongside an init script needs a version,
unlike one in a build whose settings script applies the settings plugin (see
[Other ways to apply the plugin](#other-ways-to-apply-the-plugin)).

## Samples

Have a look at
[`examples/kotlin`](https://github.com/ben-manes/gradle-versions-plugin/tree/master/examples/kotlin)
and
[`examples/groovy`](https://github.com/ben-manes/gradle-versions-plugin/tree/master/examples/groovy)

```bash
# Publish the latest version of the plugin to mavenLocal()
$ ./gradlew publishToMavenLocal

# Try out the samples
$ ./gradlew -p examples/kotlin dependencyUpdates
$ ./gradlew -p examples/groovy dependencyUpdates
```

## Compatibility

The plugin requires Gradle 8.4 or later, checked when the plugin is applied. It
targets Java 8 bytecode, so it runs on any JVM that can run Gradle itself.
Parallel execution, the configuration cache, configure on demand, and isolated
projects (see [Isolated projects](#isolated-projects)) are supported.

## Migrating from prior versions

To migrate to the current version, start at the section for the version your
build is on and work upward. Each section migrates to the version covered by
the section above it, and the topmost migrates to the current release.
*Important*s are must-dos, *Tip*s are actions you should or may want to take,
and *Note*s are things worth knowing that need no action.

### v0.62.0

In v0.63.0, the latest patch and minor versions are printed in each row, OkHttp
is no longer on the buildscript classpath, and the versions Gradle sets for its
embedded Kotlin are left out of the report:

> [!IMPORTANT]
> - A row in the plain text report can now include the latest patch and minor
>   versions between the current version and the latest one, as
>   `[1.10.18 -> 1.10.19 -> 1.11.5 -> 2.1.6]`. The versions in a row are not
>   labeled, so a tool that reads a version by its position in the row has to
>   read `available` from the JSON or XML report instead (see [Latest patch and
>   minor versions](#latest-patch-and-minor-versions)).
> - OkHttp is no longer on the buildscript classpath of a build that applies
>   the plugin. Where OkHttp classes are used in a build script, OkHttp now has
>   to be declared on its buildscript classpath.

> [!TIP]
> - The [Kotlin Gradle Plugin](#kotlin-gradle-plugin) and [Android Gradle
>   Plugin](#android-gradle-plugin) filters are now written for
>   `filterDeclaredConfigurations`. A rule set on the task that writes the
>   report is applied to the entries merged from included builds as well, but
>   `filterConfigurations` has to be declared in each build (see [Choosing
>   between them](#choosing-between-them)). `kotlinBouncyCastleConfiguration`
>   was added to the Kotlin set, since it is filled in a project that applies
>   the `signing` plugin.

> [!NOTE]
> - `kotlin-stdlib`, `kotlin-reflect` and `kotlin-scripting-compiler-embeddable`
>   at the embedded Kotlin version, where they are added by `kotlin-dsl` or
>   `embedded-kotlin` rather than declared in the build, and the `kotlin-dsl`
>   plugins at the version paired with the running Gradle on a buildscript or
>   settings classpath, are no longer printed. Set `checkEmbeddedKotlin = true` to
>   print them, or pass `--check-embedded-kotlin` for a single run (see
>   [Embedded Kotlin](#embedded-kotlin)).
> - The latest patch and minor versions are resolved as the latest version is,
>   so a `rejectVersionIf` filter or `componentSelection` rule is called again
>   for each of them.
> - Credentials from a registered `java.net.Authenticator` are now sent with the
>   Gradle update check to a host that responds with a 401, or a proxy that
>   responds with a 407. A redirect to another scheme is no longer followed.

### v0.61.0

In v0.62.0, a pre-release candidate is printed as a step after the newest
release rather than in place of it, the report is restricted to the bounds
written in the build without a rule to apply them, a coordinate with one
declared version and different latest versions across the aggregated projects
is printed on one entry per latest version, where the entries were merged into
the newest of them before, the settings configured on an aggregating task are
applied to the entries merged from an included build, and the platform behind
a constrained module's version is printed on the entry for that module:

> [!IMPORTANT]
> - A row's `available` version is now the newest release, and a newer
>   pre-release is printed beside it in `available.preRelease`, where the
>   pre-release was the `available` version before. Both are printed in a
>   plain text row, as `[2.4.0 -> 2.4.10 -> 2.4.20-beta1]`. Reading
>   `available.release`, `available.milestone` or `available.integration`
>   yields the newest release rather than the pre-release, and null where the
>   only newer candidate is a pre-release, since no newer release was found. A
>   tool that prints one version for the row falls back to
>   `available.preRelease` after those three. Set `rejectPreReleases = true`
>   to leave the pre-release step out altogether, or pass
>   `--reject-pre-releases` for a single run (see [Filtering unstable
>   versions](#filtering-unstable-versions)).
> - An attribution line reading `constrained by the platform :platform`, with
>   the platform project or BOM behind the version, can now be printed under
>   an entry. It does not depend on `checkConstraints`, and it is printed
>   under an up to date entry as well as an outdated one, so a tool that
>   parses the plain text report line by line has to skip it, as it already
>   does for the other attribution lines (see [Report
>   format](#report-format)). The same names are printed in the JSON and XML
>   reports, in `constrainedBy` (see [Constraints](#constraints)).

> [!TIP]
> - The `isNonStable` recipe formerly recommended here can be dropped, along
>   with the `rejectVersionIf` clause that called it. The built-in check
>   matches pre-release markers rather than a stable pattern, so a qualifier
>   not in its list, such as `13.4.0.jre11`, stays in the report. A convention
>   not in the marker list, such as graphql-java's `-nf-` builds, is added to
>   the check with `preReleaseVersionIf`, so the property and its option apply
>   to it too, and it is off wherever the property is off.
> - Drop `!satisfiesDeclaredBound` from a `rejectVersionIf` rule, since the
>   bound is now applied by `rejectOutOfBounds`. A module that needs an
>   exception from a bound is exempted with `exemptFromBuiltInChecksIf` (see
>   [Filtering unstable versions](#filtering-unstable-versions)). The member
>   is deprecated and will be removed in a later release; a warning is printed
>   once per project where a rule still reads `satisfiesDeclaredBound`, so the
>   clause has to be dropped before upgrading a Kotlin DSL build configured to
>   treat compiler warnings as errors. With the clause still in a rule, the
>   same candidates are rejected under `--no-reject-out-of-bounds` as without
>   it.

> [!NOTE]
> - A `rejectVersionIf` filter is now applied to a pre-release candidate
>   before the built-in check leaves it out, where the built-in check ran
>   first and no pre-release candidate reached the filter. A filter with a
>   side effect now runs for those candidates too (see [Filtering unstable
>   versions](#filtering-unstable-versions)).
> - `gradleReleaseChannel`'s default is now read from `rejectPreReleases`, so
>   the Gradle release candidate is no longer reported in a build where every
>   dependency's pre-release step is left out. The default is unchanged at
>   `release-candidate` where `rejectPreReleases` is left alone, and stating
>   the property or passing the option is still read ahead of it (see [Gradle
>   release channel](#gradle-release-channel)).
> - A candidate outside a `strictly` or `reject` bound written in the build,
>   outside a dynamic version declared on the buildscript classpath, or outside
>   the version fixed by a consumed platform, is left out of the report, where
>   it was listed before. Set `rejectOutOfBounds = false` to list it again, or
>   pass `--no-reject-out-of-bounds` for a single run (see [Respecting declared
>   bounds](#respecting-declared-bounds)).
> - A coordinate's group and name can now appear on two entries of one report,
>   and in two of its sections. The projects for each entry are included in
>   `projects` (see [Multi-project builds](#multi-project-builds)), which is
>   what distinguishes the entries. A tool that keys them by group and name
>   alone has to key them by the projects as well.
> - Every project of the build declared in a `dependencyUpdatesAggregation`
>   entry is now merged, where only the project the coordinates resolved to
>   was merged before. In a composite where every project of an included build
>   was declared, the build alone can be declared now (see [Composite
>   builds](#composite-builds)).
> - The `rejectVersionIf`, `resolutionStrategy`, `rejectPreReleases`,
>   `preReleaseVersionIf`, `exemptFromBuiltInChecksIf` and
>   `filterDeclaredConfigurations` configured on the aggregating task are
>   applied to every entry in the report, both the ones resolved in an
>   included build and the ones resolved in its subprojects. Before, the
>   version printed for a merged entry came from the settings configured in
>   the included build or the subproject. Now an older version can be printed
>   on such an entry, and an entry can be left out entirely. Where a version
>   accepted in a subproject is rejected for the project the report is asked
>   for, two entries a per-project rule would have split apart are printed as
>   one.
> - The configuration cache entry is discarded for a report that merges an
>   included build's entries, where a Kotlin rule calls a function declared in
>   the same build script. Move the function into a compiled class, in `buildSrc`
>   or an included build, to keep the entry. A Groovy build is unaffected.
> - Newer pre-releases are still reported when the current version is itself a
>   pre-release.
> - A module with only pre-releases published, and the declared version no
>   longer among them, is reported as unresolved rather than as up to date,
>   since every candidate is rejected.
> - For a plugin versioned inline as a range in the `plugins` block, the
>   presence of a version catalog no longer changes whether an upgrade past
>   that range is printed. The upgrade was left out when an unused alias for
>   the same plugin was present in the catalog, and printed when it was not
>   (see [Respecting declared bounds](#respecting-declared-bounds)).
> - A module that a platform bounds in one project and not in another is now
>   up to date in the bounded project and outdated in the other, rather than
>   outdated in both. An upgrade not available in the bounded project was
>   printed on the merged entry.
> - A split entry can include an attribution line left out of the merged one.
>   The line is left out where the module is declared in a project outside the
>   platform's importers. Once the entries are split, that project appears on
>   an entry of its own, so the line is printed again (see [Report
>   format](#report-format)).
> - A version resolved in no build is printed on an entry moved to an older
>   version by the aggregating build's rules. A version accepted in a build
>   came through the full status-aware resolution there, and a version below
>   it did not.
> - A fourth `preRelease` argument was added to `VersionAvailable`, and a
>   further `constrainedBy` one to `Dependency`, `DependencyOutdated`,
>   `DependencyLatest` and `DependencyUnresolved`. Every constructor arity and
>   every `copy` the last release shipped is still callable, so Java and Groovy
>   callers are unaffected. Kotlin code that constructs one of these while leaving an
>   argument to its default has to be recompiled. A formatter that only reads
>   the report, as the documented ones do, needs nothing.

### v0.60.0

In v0.61.0, a project is printed as its path in the build tree wherever one
appears in the report, so the projects of an included build no longer share the
`:` that stands for each build's own root. The platforms a build imports through
its own platform projects are reported, as are the configurations left
uninspected by a `resolutionStrategy` that throws:

> [!IMPORTANT]
> - A project of an included build is printed as its build tree path, so an
>   entry reads `declared in :child` where it read `declared in root project`
>   (see [Multi-project builds](#multi-project-builds)). The same paths appear
>   in the JSON and XML reports, in `projects`.
> - A `buildSrc` build is printed the same way when its task is run from the
>   outer build, as `:buildSrc:dependencyUpdates`. Its report is headed
>   `:buildSrc` rather than `:`, and its projects are printed beneath that path.
>   Running the task from inside `buildSrc` makes it the root of its own build
>   tree, which still reads `:`.
> - An attribution line reading `imported by the platform :platforms` can now
>   be printed under an entry, showing the platform projects the build imports
>   the dependency through. A tool that parses the plain text report line by
>   line has to skip it, as it already does for the other attribution lines (see
>   [Report format](#report-format)). The same paths appear in the JSON and XML
>   reports, in `platformProjects`.
> - The plain text report now has a section listing the configurations that
>   could not be inspected, after the dependency ones, where they were logged at
>   info level and left out silently before. They appear in the JSON and XML
>   reports under `skipped`, which
>   `count` does not include, so a tool totalling the report has to leave it out
>   too (see [Report output](#report-output)).
> - A custom `outputFormatter` that calls `copy` on `DependencyOutdated`,
>   `DependencyLatest` or `DependencyUnresolved` has to be recompiled against
>   this release. Each gained a `platformProjects` property, and a data class
>   has exactly one `copy`, so the one earlier releases shipped is gone. Their
>   constructors are unchanged: a formatter that only reads the report, as the
>   documented ones do, needs nothing.

> [!TIP]
> An entry can show a configuration name that `filterConfigurations` cannot
> match, such as a declarable configuration read through a resolvable classpath
> that extends it. Rejecting the classpath instead removes the build's own
> dependencies with it.
> [`filterDeclaredConfigurations`](#filterdeclaredconfigurations) rejects the
> entry by the name it shows, and leaves what the task checks alone.

> [!NOTE]
> - A dependency that two builds of a composite declare at different versions is
>   now reported as divergent. The attribution showing the projects for each
>   version was left out while every entry had the same project path, which in a
>   composite was always `:`.
> - With `checkConstraints`, an entry is now printed for each platform the
>   build's own platform projects import. The modules those constraints bound
>   were reported as up to date, with nothing to show the coordinate to bump
>   (see [Constraints](#constraints)).
> - A configuration that declares a platform is resolved a second time, to find
>   those imports, so a `beforeResolve` hook on it runs once more than it did. A
>   configuration declaring no platform resolves nothing extra.

### v0.59.0

In v0.60.0, the configuration a dependency was declared directly against is
printed, so a plugin that fills a classpath of its own when it is applied no
longer reads as the build declaring the dependency. The reason a dependency
constraint was declared with is printed too, and a component selection rule can
keep the report inside the bound the build declared:

> [!IMPORTANT]
> - An attribution line can now be printed under an entry that had none,
>   showing the configuration the dependency was declared against. A tool that
>   parses the plain text report line by line has to skip it, as it already does
>   for the other attribution lines (see [Report format](#report-format)). The
>   same names appear in the JSON and XML reports, in `configurations`, which
>   until now contained only what a plugin contributed, so a tool reading that
>   field has to read `contributed` alongside it to tell the two apart.
> - The reason on a dependency constraint declared with `because` is now
>   printed, where only a dependency's reason appeared before. It prints on the
>   same line as a dependency's reason does, so a tool that already handles one
>   handles both.

> [!TIP]
> - A component selection rule can keep the report inside what the build
>   declared, with `rejectVersionIf { !satisfiesDeclaredBound }`. A module
>   bounded by a `strictly` or by a platform the build consumes is then bounded
>   there, so only versions the build can actually take are offered (see
>   [Respecting declared bounds](#respecting-declared-bounds)).
> - A Kotlin DSL build script that worked around the Gradle 9 overload ambiguity
>   by spelling out `Action<ComponentSelectionWithCurrent>` can go back to the
>   untyped `all { }` and `withModule(id) { }` forms.

### v0.58.0

In v0.59.0, the version another resolution found for a dependency that failed
to resolve is reported, and the partial result of every project is collected
under the project that aggregates them, so no `build` directory is created in a
project that exists only for a nested `include`:

> [!IMPORTANT]
> * A declared version that resolved in one place and failed in another is now
>   reported twice: once with the version found for it, and again as
>   unresolved. A dependency declared without a version doubles the same way, as
>   undeclared and unresolved. The JSON and XML reports count it in each place.
>   A tool reading `count` as a dependency total, or treating the sections as
>   disjoint, has to allow for the overlap (see
>   [Report format](#report-format)).
> * The unresolved section of the plain text report now includes the declared
>   version, as every other section does. A tool that parses that section line
>   by line has to allow it.

> [!TIP]
> Run `./gradlew dependencyUpdates --clean-legacy-partials` once to remove the
> `build/dependencyUpdates/partial.json` that earlier releases wrote into each
> project. The `clean` task removes it from a project that directly or
> indirectly applies the
> [Base plugin](https://docs.gradle.org/current/userguide/base_plugin.html), but
> a project with no build script has no `clean` task.

### v0.57.0

In v0.58.0, the reporters print through the build's logger, and attribution
lines are added to the reports:

> [!IMPORTANT]
> * The console summary now prints at the lifecycle log level, so `--quiet`
>   suppresses it. The report file is still written; read it, or drop
>   `--quiet`, if a script was piping the console output (see
>   [Report format](#report-format)).
> * An indented attribution line may be printed under an entry, showing the
>   projects that declared a divergent version (see
>   [Multi-project builds](#multi-project-builds)) or the configuration a
>   plugin contributed it into (see
>   [The `dependencyUpdates` task](#the-dependencyupdates-task)). A tool that
>   parses the plain text report line by line has to skip them; the same
>   information appears as fields in the JSON and XML reports.

### v0.56.0

In v0.57.0 the settings plugin can be applied from an init script:

> [!IMPORTANT]
> An init script that applies `VersionsPlugin` to `allprojects` reports per
> project rather than once, omits the plugins that the settings script declares,
> and fails under isolated projects. Apply the settings plugin from
> `beforeSettings` instead (see
> [Initialization script](#initialization-script)).

### v0.55.0

In v0.56.0 the settings plugin is added, and it becomes the recommended setup:

> [!TIP]
> Apply `io.github.ben-manes.versions.settings` in the settings script (see
> [Applying the plugin](#applying-the-plugin)) and remove
> `io.github.ben-manes.versions` from your build scripts. A project that keeps
> the main plugin for a separate per-project report must request it without a
> version, because the settings plugin already puts the plugin on every
> project's classpath.

> [!NOTE]
> * A build that applied `io.github.ben-manes.versions.contributor` from a
>   convention plugin for isolated projects support no longer needs it: the
>   settings plugin covers every project. The contributor plugin remains
>   available for builds that cannot apply a settings plugin.
> * Task configuration is unchanged: configure `dependencyUpdates` in the root
>   build script as before.

### v0.54.0 and earlier

In v0.55.0 the plugin moves from the `com.github.ben-manes` namespace to
`io.github.ben-manes`, and the minimum supported Gradle version rises to 8.4:

> [!IMPORTANT]
> * Move a `buildscript` or `initscript` `classpath` dependency to the
>   `io.github.ben-manes:gradle-versions-plugin` coordinate. v0.54.0 is the
>   last release published under
>   `com.github.ben-manes:gradle-versions-plugin`, so no further updates are
>   published under the old coordinate.
> * Under the configuration cache, a custom `outputFormatter` cannot reach the
>   project or the build script from inside the closure, because it runs at
>   execution time (see [Report format](#report-format)). Read what it needs
>   into local variables inside the `configure` block, and use the
>   `PlainTextReporter` constructor that takes the project path, as shown
>   below.
>
> <details open>
> <summary>Kotlin</summary>
>
> "build.gradle.kts":
> ```kotlin
> import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
> import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
>
> tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
>   val projectPath = project.path
>
>   outputFormatter {
>     PlainTextReporter(projectPath, revision, gradleReleaseChannel).write(System.out, this)
>   }
> }
> ```
>
> </details>
>
> <details>
> <summary>Groovy</summary>
>
> "build.gradle":
> ```groovy
> import com.github.benmanes.gradle.versions.reporter.PlainTextReporter
>
> tasks.named("dependencyUpdates").configure {
>   def projectPath = project.path
>   def taskRevision = revision
>   def releaseChannel = gradleReleaseChannel
>
>   outputFormatter { result ->
>     new PlainTextReporter(projectPath, taskRevision, releaseChannel).write(System.out, result)
>   }
> }
> ```
>
> </details>
>
> Groovy also needs `revision` and `gradleReleaseChannel` read up front, because
> the closure is coerced to an `Action` without a delegate. In a precompiled
> script plugin a top-level `val` is a field of the script, so hoisting the
> value out of the `configure` block does not work.

> [!TIP]
> Switch the plugin ID from `com.github.ben-manes.versions` to
> `io.github.ben-manes.versions`. The legacy ID is deprecated but keeps
> receiving releases, so this can happen at your convenience; only the main
> plugin has a legacy ID.

> [!NOTE]
> v0.55.0 also reworks how the merged report of a multi-project build is
> produced: it is aggregated from a task in each project, which adds support for
> parallel execution, the configuration cache, and isolated projects (see
> [Multi-project builds](#multi-project-builds)). The report content is
> unchanged, as is task configuration apart from a custom `outputFormatter`
> under the configuration cache.

Then continue with the v0.55.0 steps above.

## Related plugins

This plugin only reports. To apply the updates it finds automatically:

* [version-catalog-update-plugin](https://github.com/littlerobots/version-catalog-update-plugin):
  updates the versions in your version catalog (`libs.versions.toml`) based on
  this plugin's report
* [gradle-use-latest-versions](https://github.com/patrikerdes/gradle-use-latest-versions-plugin):
  updates versions declared directly in build scripts based on this plugin's
  report
* [gradle-upgrade-interactive](https://github.com/kevcodez/gradle-upgrade-interactive):
  interactive CLI that applies the updates you select from this plugin's
  report

Other related tools:

* [gradle-versions-filter-plugin](https://github.com/janderssonse/gradle-versions-filter-plugin)
* [gradle-update-checker](https://github.com/marketplace/actions/gradle-update-checker)
* [gradle-libraries-plugin](https://github.com/fkorotkov/gradle-libraries-plugin)
* [gradle-update-notifier](https://github.com/y-yagi/gradle-update-notifier)
* [refreshVersions](https://github.com/jmfayard/refreshVersions)
* [update-versions-gradle-plugin](https://github.com/tomasbjerre/update-versions-gradle-plugin)
* [caupain](https://github.com/deezer/caupain/)
