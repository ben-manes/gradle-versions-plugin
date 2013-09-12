# Gradle Versions Plugin

In the spirit of the [Maven Versions Plugin](http://mojo.codehaus.org/versions-maven-plugin/), 
this plugin provides a task to determine which dependencies have updates.

## Usage

This plugin is hosted on the Maven Central Repository. You can add it to your build script using
the following configuration:

```groovy
apply plugin: 'versions'

buildscript {
  repositories {
    mavenCentral()
  }
  
  dependencies {
    classpath 'com.github.ben-manes:gradle-versions-plugin:0.4'
  }
}
```

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

This displays a report to the console, e.g.

```
------------------------------------------------------------
: Project Dependency Updates
------------------------------------------------------------

The following dependencies are using the latest release version:
 - com.google.code.findbugs:jsr305:2.0.1
 - com.google.inject:guice:3.0
 - com.google.inject.extensions:guice-multibindings:3.0
 - com.google.inject.extensions:guice-servlet:3.0

The following dependencies exceed the version found at the release revision level:
 - org.scalatra:scalatra [2.3.0-SNAPSHOT <- 2.2.0-RC1]
 - org.scalatra:scalatra-auth [2.3.0-SNAPSHOT <- 2.2.0-RC1]
 - org.scalatra:scalatra-specs2 [2.3.0-SNAPSHOT <- 2.2.0-RC1]

The following dependencies have later release versions:
 - com.amazonaws:aws-java-sdk [1.3.21.1 -> 1.3.26]
 - com.beust:jcommander [1.27 -> 1.30]
```

