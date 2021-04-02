package com.github.benmanes.gradle.versions

import groovy.transform.CompileStatic

/**
 * Plugin extension (i.e. configuration options).
 */
@CompileStatic
class VersionsPluginExtension {

  String defaultSuffix = 'SNAPSHOT'

  Boolean skipVersionUpdate = false

}
