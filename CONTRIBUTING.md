# Contributing

## Sending a change

Fork the repository, branch from `master`, and open a pull request back
against `master`.

- Every push to the pull request runs the
  [build workflow](.github/workflows/build.yml) on JDK 8, 11 and 17. A change
  touching only `README.md` or `CONTRIBUTING.md` skips it.
- Reference the issue a change addresses in the commit summary and in the
  pull request body, so the merge closes it:

  ```text
  Set JVM attribute on copied configuration (fixes #727)
  ```

- Keep each commit to one logical change.
- A change without an issue behind it is welcome, but for anything beyond a
  small fix, open an issue first: the report is where the behavior gets
  agreed on, and it saves rewriting a pull request that solved the wrong
  problem.

How a pull request lands:

- Several commits are squashed under a subject describing the whole change,
  so the commits are yours to organize as you like while the pull request is
  open.
- A single commit is usually squashed as well, which appends its number, and
  is rebased where the commit message should land word for word.
- Merge commits are not used.

## Building and testing

- `./gradlew build` compiles the plugin and runs its Spock specs, including
  the functional tests that drive real Gradle builds through TestKit.
- On Windows, `gradlew.bat` stands in for `./gradlew` throughout.
- The plugin targets Java 8 bytecode: it runs in the Gradle daemon's process,
  and our minimum supported Gradle version of 8.4 still supports Java 8. A
  change that needs a newer API needs a guard, and raising the target means
  raising the minimum Gradle first.
- To try a change against a real build, publish it under a version of its
  own and point the consumer at that:

  ```bash
  ./gradlew publishToMavenLocal -PVERSION_NAME=0.59.0-SNAPSHOT
  ```

- A bug fix carries a regression test that fails without it, tagged with the
  issue it closes:

  ```groovy
  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/475')
  def 'Single project with a snapshot-only module reports up to date'() {
  ```

## Style

- `./gradlew ktlintCheck` runs as part of the build and is the mechanical
  gate; `.editorconfig` sets two-space indentation and LF endings.
- Beyond that, match the file you are editing: Kotlin sources carry no
  license header, comments are rare and explain only what the code cannot,
  and a README snippet is added in both Kotlin and Groovy.
- Add a command line option, with `@Option`, to every task property a build
  author sets to configure the report or where it is written, so that a single
  run can change it without an edit to the build script. A property configured
  with a predicate has no option, since no command line can express the logic.
  The system properties named after `revision`, `gradleReleaseChannel`,
  `outputFormatter`, `outputDir` and `reportfileName` predate the options and
  remain for compatibility; where more than one is set, the option is the one
  that applies, and no system property is added for a new property.
- Gradle applies a command line option by calling a setter on the task. Have
  that setter assign a separate field, not the field assigned from a build
  script, and read them in order: the command line value first, then the system
  property if there is one, then the configured value. Assigning the same field
  fails three ways. A system property is read before the configured value, so
  the value from `-Drevision` would be used instead of the one from
  `--revision`. The configured value is read from the nearest project up the
  hierarchy where one is set, so an option given on the root task would not
  apply to a subproject that sets its own. And a `taskGraph.whenReady` or
  `doFirst` block assigns the property after Gradle has applied the options,
  overwriting the value from the command line.
- An option applies only within the build it is invoked in. In a report that
  merges an included build, that build's rows are resolved from that build's own
  configuration, so they are unaffected by an option; document any behavior that
  differs between the two in the README's [Composite
  builds](README.md#composite-builds) section.
- A change that an existing build has to react to, such as different report
  output or a behavior that is now off by default, also gets a subsection in
  the README's
  [Migrating from prior versions](README.md#migrating-from-prior-versions),
  written for someone upgrading from the last release. A subsection opens with
  a sentence naming what the release changed, then carries at most one alert of
  each kind, picked and ordered the same way as in the [release
  notes](#releasing). An alert covering more than one item takes a bullet per
  item rather than repeating the alert, and a snippet the items call for sits
  inside that alert, at the bottom when more than one item shares it.

## Releasing

For maintainers. Releases are published to the [Gradle Plugin
Portal](https://plugins.gradle.org/plugin/io.github.ben-manes.versions) by the
[deploy workflow](.github/workflows/deploy.yml). Dispatching it at a tag is the
whole release: it builds that tag, runs `publishPlugins` against it, waits for
the portal to serve the new version, and only then publishes the drafted
release. Each step gates the one after it, so a failure stops short of
notifying watchers.

Prerequisites:

- Push access to the repository.
- The [GitHub CLI](https://cli.github.com/) (`gh`), authenticated with
  `gh auth login`.
- A POSIX shell. On Windows, run the steps from
  [Git Bash](https://gitforwindows.org/).

`vX.Y.Z` below stands for the version being released, so replace it in every
command. The tag carries the `v`; `VERSION_NAME` and the portal's metadata do
not.

Always create the GitHub release as a draft:

- A release cannot be un-notified, so nothing may notify watchers until the
  portal has accepted the artifacts.
- A draft does not notify, and the workflow publishes it once the portal
  serves the version.

1. Set `VERSION_NAME` in `gradle.properties` to the release version and merge
   it to `master` as `Prepare the vX.Y.Z release`.

   - Keep the bump alone in its own pull request, and to a single commit, so
     rebasing is the obvious way to merge it. A rebase lands the subject
     verbatim; the squash button appends the pull request number to it.
   - Squash locally and force-push if the branch gained commits along the way.
     Nothing reads the subject, so a squashed bump still releases, but the
     version reads better without a pull request number beside it.
   - Anything else the release needs, migration notes above all, belongs to
     the pull request whose change called for it.

2. Tag that commit and push the tag:

   ```bash
   git fetch origin
   git tag vX.Y.Z origin/master
   git push origin vX.Y.Z
   ```

   - Tag `origin/master`, not a local checkout: the merge landed on GitHub,
     and a local `master` may be behind.

3. Draft the release at the tag:

   ```bash
   gh release create vX.Y.Z --draft --verify-tag --notes-file release-notes.md
   ```

   - `--verify-tag` aborts when the tag does not exist on GitHub. A typoed
     tag would otherwise become a real one, cut from `master`, the moment the
     draft publishes.
   - `release-notes.md` is a scratch file: one bullet per change carrying its
     issue and pull request numbers, then an alert for anything an existing
     build has to react to. Lines are not wrapped, since GitHub reflows them.
   - Pick the alert by what the reader has to do, and order them as listed:
     - `> [!IMPORTANT]` for a step the reader must take, including one that
       applies only to builds using a particular feature.
     - `> [!TIP]` for a step the reader may want to take, such as a new
       recommended approach where the old one still works.
     - `> [!NOTE]` for what is worth knowing but needs no action.
   - The text starts on the line after the marker, with no blank `>` between.

   ```markdown
   * Fixed a pesky bug (#101, #104)
   * Added an awesome feature (#102, #105)

   > [!IMPORTANT]
   > The `frobnicate` option is gone. Replace it with `reticulate`.

   > [!TIP]
   > The new `splines` extension is the recommended way to configure the task.

   > [!NOTE]
   > The `dependencyUpdates` task now reticulates splines. See [Migrating from prior versions](https://github.com/ben-manes/gradle-versions-plugin#vXYZ).
   ```

4. Release by dispatching the workflow at the tag, then watch the run:

   ```bash
   gh workflow run deploy.yml --ref vX.Y.Z
   gh run watch --exit-status
   ```

   - `--ref` has to name the tag: a manual run checks out the dispatched ref.
   - `gh run watch` picks from the runs in progress, so give the dispatch a few
     seconds to register. It exits non-zero when the run fails.
   - The workflow refuses a ref whose `VERSION_NAME` does not match the tag,
     so a dispatch that forgets `--ref` fails instead of publishing `master`.
   - It builds the tag, which is where the release gets its test run. The bump
     lands by rebase, so the pull request's checks ran on a commit the tag does
     not carry, and a commit touching only `README.md` or `CONTRIBUTING.md`
     merged after them triggers no build workflow at all. A failure here leaves
     the portal untouched: fix `master`, move the tag, and dispatch again.
   - It waits up to ten minutes for the portal to serve the version, since the
     portal takes a few minutes to index what `publishPlugins` uploaded.
   - It publishes the release last, which is what notifies watchers. A run that
     fails before then leaves the release a draft, so dispatch again once the
     cause is fixed.
   - Dispatch again only while the portal is still not serving the version. The
     portal refuses a version it already has, so a run that failed after the
     upload succeeded cannot be repeated. Publish the release by hand instead:

     ```bash
     gh release edit vX.Y.Z --draft=false --latest
     ```

### Releasing a new plugin id

A new plugin id, or a change of Maven group, goes through the portal's manual
approval queue. The steps above still apply, with these changes:

- Give the new id the `io.github` group. The portal refuses a new `com.github`
  id, and one refused id fails the whole publication (#997, #998).
- Batch new ids into one release where possible. The review covers every id
  in the publication, so three ids reviewed together is one wait; three
  releases is three.
- Review could take a few days. You can file a [Gradle Plugin Portal Requests 
  issue](https://github.com/gradle/plugin-portal-requests/issues) 
  (e.g., https://github.com/gradle/plugin-portal-requests/issues/298) if you 
  don't receive a timely response.
- Do not trust a green deploy run: `publishPlugins` succeeds while the
  coordinate is held. Keep the release drafted until the new id's marker
  serves the version, which step 5's URL does not cover:

  ```bash
  curl -sf https://plugins.gradle.org/m2/<id as a path>/<id>.gradle.plugin/maven-metadata.xml \
    | grep -qF '<version>X.Y.Z</version>' && echo "published" || echo "not published"
  ```
