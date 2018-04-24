[ ![Download](https://api.bintray.com/packages/fooberger/maven/com.github.ben-manes%3Agradle-versions-plugin/images/download.svg) ](https://bintray.com/fooberger/maven/com.github.ben-manes%3Agradle-versions-plugin/_latestVersion)

# Gradle Versions Plugin

In the spirit of the [Maven Versions Plugin](http://www.mojohaus.org/versions-maven-plugin/),
this plugin provides a task to determine which dependencies have updates.

You may also wish to explore additional functionality provided by,
 - [gradle-use-latest-versions](https://github.com/patrikerdes/gradle-use-latest-versions-plugin)
 - [gradle-libraries-plugin](https://github.com/fkorotkov/gradle-libraries-plugin)
 - [gradle-update-notifier](https://github.com/y-yagi/gradle-update-notifier)

## Usage

This plugin is available from [Bintray's JCenter repository](http://jcenter.bintray.com). You can
add it to your top-level build script using the following configuration:

### `plugins` block:

```groovy
plugins {
  id "com.github.ben-manes.versions" version "$version"
}
```
or via the

### `buildscript` block:
```groovy
apply plugin: "com.github.ben-manes.versions"

buildscript {
  repositories {
    jcenter()
  }

  dependencies {
    classpath "com.github.ben-manes:gradle-versions-plugin:$version"
  }
}
```

The current version is known to work with Gradle versions up to 4.6.

## Tasks

### `dependencyUpdates`

Displays a report of the project dependencies that are up-to-date, exceed the latest version found,
have upgrades, or failed to be resolved. When a dependency cannot be resolved the exception is
logged at the `info` level.

#### Revisions

The `revision` task property controls the resolution strategy of determining what constitutes the
latest version of a dependency. The following strategies are supported:

  * release: selects the latest release
  * milestone: select the latest version being either a milestone or a release (default)
  * integration: selects the latest revision of the dependency module (such as SNAPSHOT)

The strategy can be specified either on the task or as a system property for ad hoc usage:

```groovy
gradle dependencyUpdates -Drevision=release
```

The latest versions can be further filtered using [Component Selection Rules][component_selection_rules].
For example to disallow release candidates as upgradable versions a selection rule could be defined as:

```groovy
dependencyUpdates.resolutionStrategy = {
  componentSelection { rules ->
    rules.all { ComponentSelection selection ->
      boolean rejected = ['alpha', 'beta', 'rc', 'cr', 'm'].any { qualifier ->
        selection.candidate.version ==~ /(?i).*[.-]${qualifier}[.\d-]*/
      }
      if (rejected) {
        selection.reject('Release candidate')
      }
    }
  }
}
```

#### Report format

The task property `outputFormatter` controls the report output format. The following values are supported:

  * `"plain"`: format output file as plain text (default)
  * `"json"`: format output file as json text
  * `"xml"`: format output file as xml text, can be used by other plugins (e.g. sonar)
  * a `Closure`: will be called with the result of the dependency update analysis (see [example below](#custom_report_format))

You can also set multiple output formats using comma as the separator:

```groovy
gradle dependencyUpdates -Drevision=release -DoutputFormatter=json,xml
```

The task property `outputDir` controls the output directory for the report  file(s). The directory will be created if it does not exist.
The default value is set to `build/dependencyUpdates`

```groovy
gradle dependencyUpdates -Drevision=release -DoutputFormatter=json -DoutputDir=/any/path/with/permission
```

Last the property `reportfileName` sets the filename (without extension) of the generated report. It defaults to `report`.
The extension will be set according to the used output format.

```groovy
gradle dependencyUpdates -Drevision=release -DoutputFormatter=json -DreportfileName=myCustomReport
```

This displays a report to the console, e.g.

```
------------------------------------------------------------
: Project Dependency Updates (report to plain text file)
------------------------------------------------------------

The following dependencies are using the latest integration version:
 - backport-util-concurrent:backport-util-concurrent:3.1
 - backport-util-concurrent:backport-util-concurrent-java12:3.1

The following dependencies exceed the version found at the integration revision level:
 - com.google.guava:guava [99.0-SNAPSHOT <- 16.0-rc1]
   http://code.google.com/p/guava-libraries
 - com.google.guava:guava-tests [99.0-SNAPSHOT <- 16.0-rc1]
   http://code.google.com/p/guava-libraries

The following dependencies have later integration versions:
 - com.google.inject:guice [2.0 -> 3.0]
   http://code.google.com/p/google-guice/
 - com.google.inject.extensions:guice-multibindings [2.0 -> 3.0]
   http://code.google.com/p/google-guice/
```

Json report
```json
{
  "current": {
    "dependencies": [
      {
        "group": "backport-util-concurrent",
        "version": "3.1",
        "name": "backport-util-concurrent",
        "projectUrl": "http://backport-jsr166.sourceforge.net/"
      },
      {
        "group": "backport-util-concurrent",
        "version": "3.1",
        "name": "backport-util-concurrent-java12",
        "projectUrl": "http://backport-jsr166.sourceforge.net/"
      }
    ],
    "count": 2
  },
  "exceeded": {
    "dependencies": [
      {
        "group": "com.google.guava",
        "latest": "16.0-rc1",
        "version": "99.0-SNAPSHOT",
        "name": "guava",
        "projectUrl": "http://code.google.com/p/guava-libraries"
      },
      {
        "group": "com.google.guava",
        "latest": "16.0-rc1",
        "version": "99.0-SNAPSHOT",
        "name": "guava-tests",
        "projectUrl": "http://code.google.com/p/guava-libraries"
      }
    ],
    "count": 2
  },
  "outdated": {
    "dependencies": [
      {
        "group": "com.google.inject",
        "available": {
          "release": "3.0",
          "milestone": null,
          "integration": null
        },
        "version": "2.0",
        "name": "guice",
        "projectUrl": "http://code.google.com/p/google-guice/"
      },
      {
        "group": "com.google.inject.extensions",
        "available": {
          "release": "3.0",
          "milestone": null,
          "integration": null
        },
        "version": "2.0",
        "name": "guice-multibindings",
        "projectUrl": "http://code.google.com/p/google-guice/"
      }
    ],
    "count": 2
  },
  "unresolved": {
    "dependencies": [
      {
        "group": "com.github.ben-manes",
        "version": "1.0",
        "reason": "Could not find any version that matches com.github.ben-manes:unresolvable:latest.milestone.",
        "name": "unresolvable"
      },
      {
        "group": "com.github.ben-manes",
        "version": "1.0",
        "reason": "Could not find any version that matches com.github.ben-manes:unresolvable2:latest.milestone.",
        "name": "unresolvable2"
      }
    ],
    "count": 2
  },
  "count": 8
}
```

XML report
```xml
<response>
  <count>8</count>
  <current>
    <count>2</count>
    <dependencies>
      <dependency>
        <name>backport-util-concurrent</name>
        <group>backport-util-concurrent</group>
        <version>3.1</version>
        <projectUrl>http://backport-jsr166.sourceforge.net/</projectUrl>
      </dependency>
      <dependency>
        <name>backport-util-concurrent-java12</name>
        <group>backport-util-concurrent</group>
        <version>3.1</version>
        <projectUrl>http://backport-jsr166.sourceforge.net/</projectUrl>
      </dependency>
    </dependencies>
  </current>
  <outdated>
    <count>2</count>
    <dependencies>
      <outdatedDependency>
        <name>guice</name>
        <group>com.google.inject</group>
        <version>2.0</version>
        <available>
          <release>3.0</release>
        </available>
        <projectUrl>http://code.google.com/p/google-guice/</projectUrl>
      </outdatedDependency>
      <outdatedDependency>
        <name>guice-multibindings</name>
        <group>com.google.inject.extensions</group>
        <version>2.0</version>
        <available>
          <release>3.0</release>
        </available>
        <projectUrl>http://code.google.com/p/guava-libraries</projectUrl>
      </outdatedDependency>
    </dependencies>
  </outdated>
  <exceeded>
    <count>2</count>
    <dependencies>
      <exceededDependency>
        <name>guava</name>
        <group>com.google.guava</group>
        <version>99.0-SNAPSHOT</version>
        <latest>16.0-rc1</latest>
        <projectUrl>http://code.google.com/p/guava-libraries</projectUrl>
      </exceededDependency>
      <exceededDependency>
        <name>guava-tests</name>
        <group>com.google.guava</group>
        <version>99.0-SNAPSHOT</version>
        <latest>16.0-rc1</latest>
        <projectUrl>http://code.google.com/p/guava-libraries</projectUrl>
      </exceededDependency>
    </dependencies>
  </exceeded>
  <unresolved>
    <count>2</count>
    <dependencies>
      <unresolvedDependency>
        <name>unresolvable</name>
        <group>com.github.ben-manes</group>
        <version>1.0</version>
        <reason>Could not find any version that matches com.github.ben-manes:unresolvable:latest.release.</reason>
      </unresolvedDependency>
      <unresolvedDependency>
        <name>unresolvable2</name>
        <group>com.github.ben-manes</group>
        <version>1.0</version>
        <reason>Could not find any version that matches com.github.ben-manes:unresolvable2:latest.release.</reason>
      </unresolvedDependency>
    </dependencies>
  </unresolved>
</response>
```

[component_selection_rules]: https://docs.gradle.org/current/userguide/dependency_management.html#component_selection_rules

#### <a name="custom_report_format"></a>Custom report format
If you need to create a report in a custom format, you can set the `dependencyUpdates` tasks's `outputFormatter` property to a Closure. The closure will be called with a single argument that is an instance of [com.github.benmanes.gradle.versions.reporter.result.Result](src/main/groovy/com/github/benmanes/gradle/versions/reporter/result/Result.groovy).

For example, if you wanted to create an html table for the upgradable dependencies, you could use:

```groovy
...
dependencyUpdates {
  outputFormatter = { result ->
    def updatable = result.outdated.dependencies
    if (!updatable.isEmpty()){
      def writer = new StringWriter()
      def html = new groovy.xml.MarkupBuilder(writer)

      html.html {
        body {
          table {
            thead {
              tr {
                td("Group")
                td("Module")
                td("Current version")
                td("Latest version")
              }
            }
            tbody {
              updatable.each { dependency->
                tr {
                  td(dependency.group)
                  td(dependency.name)
                  td(dependency.version)
                  td(dependency.available.release ?: dependency.available.milestone)
                }
              }
            }
          }
        }
      }
      println writer.toString()
    }
  }
}

``` 
