# Gradle Versions Plugin

In the spirit of the [Maven Versions Plugin](http://mojo.codehaus.org/versions-maven-plugin/), 
this plugin provides a task to determine which dependencies have updates.

## Usage

This plugin is available from a Maven repository hosted on GitHub. You can add it to your build script using
the following configuration:

```groovy
apply plugin: 'com.github.ben-manes.versions'

buildscript {
  repositories {
    maven { url "http://dl.bintray.com/fooberger/maven/" }
    mavenCentral()
  }
  
  dependencies {
    classpath 'com.github.ben-manes:gradle-versions-plugin:0.5-beta-6'
  }
}
```
The current version is known to work with Gradle versions up to 2.0.

## Tasks

### `dependencyUpdates`

Displays a report of the project dependencies that are up-to-date, exceed the latest version found,
have upgrades, or failed to be resolved. When a dependency cannot be resolved the exception is
logged at the `info` level.

The `revision` task property controls the resolution strategy of determining what constitutes the
latest version of a dependency. The following strategies are supported:

  * release: selects the latest release
  * milestone: select the latest version being either a milestone or a release (default)
  * integration: selects the latest revision of the dependency module (such as SNAPSHOT)

The strategy can be specified either on the task or as a system property for ad hoc usage:

```groovy
gradle dependencyUpdates -Drevision=release
```

Another task property `outputFormatter` controls the report output format. The following values are supported:

  * `"plain"`: format output file as plain text (default)
  * `"json"`: format output file as json text
  * `"xml"`: format output file as xml text, can be used by other plugins (e.g. sonar)
  * a Closure: will be called with the result of the dependency update analysis

You can also set multiple output formats using comma as the separator:

```groovy
gradle dependencyUpdates -Drevision=release -DoutputFormatter=json,xml
```

Last, the task property `outputDir` controls the output directory for the report  file(s). The directory will be created if it does not exist.
The default value is set to `build/dependencyUpdates`

```groovy
gradle dependencyUpdates -Drevision=release -DoutputFormatter=json -DoutputDir=/any/path/with/permission
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
 - com.google.guava:guava-tests [99.0-SNAPSHOT <- 16.0-rc1]

The following dependencies have later integration versions:
 - com.google.inject:guice [2.0 -> 3.0]
 - com.google.inject.extensions:guice-multibindings [2.0 -> 3.0]
```

Json report
```json
{
    "current": {
        "dependencies": [
            {
                "group": "backport-util-concurrent",
                "version": "3.1",
                "name": "backport-util-concurrent"
            },
            {
                "group": "backport-util-concurrent",
                "version": "3.1",
                "name": "backport-util-concurrent-java12"
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
                "name": "guava"
            },
            {
                "group": "com.google.guava",
                "latest": "16.0-rc1",
                "version": "99.0-SNAPSHOT",
                "name": "guava-tests"
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
                "name": "guice"
            },
            {
                "group": "com.google.inject.extensions",
                "available": {
                    "release": "3.0",
                    "milestone": null,
                    "integration": null
                },
                "version": "2.0",
                "name": "guice-multibindings"
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
      </dependency>
      <dependency>
        <name>backport-util-concurrent-java12</name>
        <group>backport-util-concurrent</group>
        <version>3.1</version>
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
      </outdatedDependency>
      <outdatedDependency>
        <name>guice-multibindings</name>
        <group>com.google.inject.extensions</group>
        <version>2.0</version>
        <available>
          <release>3.0</release>
        </available>
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
      </exceededDependency>
      <exceededDependency>
        <name>guava-tests</name>
        <group>com.google.guava</group>
        <version>99.0-SNAPSHOT</version>
        <latest>16.0-rc1</latest>
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

