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
[deploy workflow](.github/workflows/deploy.yml), which builds the release tag
and then runs `publishPlugins` against it. It runs when a release is created,
and on manual dispatch.

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

- Creating it published runs the workflow and notifies watchers in the same
  moment, before anything has confirmed the portal accepted the artifacts.
- A release cannot be un-notified.
- A draft neither runs the workflow nor notifies, so the portal goes first.

1. Set `VERSION_NAME` in `gradle.properties` to the release version and merge
   it to `master` as `Prepare the vX.Y.Z release`.

   - Keep the bump alone in its own pull request, so it can be rebased rather
     than squashed and the version lands in the commit subject rather than in
     a pull request number.
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

4. Publish to the portal by dispatching the workflow at the tag:

   ```bash
   gh workflow run deploy.yml --ref vX.Y.Z
   ```

   - `--ref` has to name the tag: a manual run checks out the dispatched ref.
   - The workflow refuses a ref whose `VERSION_NAME` does not match the tag,
     so a dispatch that forgets `--ref` fails instead of publishing `master`.
   - It builds the tag before publishing, which is where the release gets its
     test run. The bump lands by rebase, so the pull request's checks ran on a
     commit the tag does not carry, and a docs-only commit merged after them
     triggers no build workflow at all. A failure here leaves the portal
     untouched: fix `master`, move the tag, and dispatch again.

5. Confirm the portal serves the new version:

   ```bash
   curl -sf https://plugins.gradle.org/m2/io/github/ben-manes/gradle-versions-plugin/maven-metadata.xml \
     | grep -qF '<version>X.Y.Z</version>' && echo "published" || echo "not published"
   ```

   - The portal takes a few minutes after the workflow succeeds. "not
     published" covers an unreachable portal too; either way, wait and run it
     again.

6. Publish the release:

   ```bash
   gh release edit vX.Y.Z --draft=false --latest
   ```

   - This notifies watchers, which is why it comes last.
   - Publishing the draft does not run the workflow again; its trigger is
     `created`, which the flip from draft to published does not fire.

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
