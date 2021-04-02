package com.github.benmanes.gradle.versions.parser

import groovy.transform.CompileStatic

import java.util.regex.Matcher

@CompileStatic
class GradlePropertiesParser {

  final File file
  final String content
  final Properties props

  GradlePropertiesParser(final File propertiesFile) {
    this.file = propertiesFile
    this.content = propertiesFile.getText()
    props = new Properties()
    propertiesFile.withInputStream { InputStream inputStream ->
      props.load(inputStream)
    }
  }

  /**
   * Get entries.
   * @return List entries.
   */
  List<Map.Entry<String, String>> getEntries() {
    return (props.entrySet()).toList() as List<Map.Entry<String, String>>
  }

  /**
   * Get all the keys with a given value.
   * @param value Value to look for.
   * @return List of keys.
   */
  List<String> getKeys(final String value) {
    return (List<String>) props.findResults { final Map.Entry entry ->
      (entry.getValue() == value) ? (String) entry.getKey() : null
    }
  }

  /**
   * Get the keys of this properties file that are contained in a given string.
   * @param string String in which to look for keys.
   * @return Set of keys.
   */
  Set<String> getKeysContainedIn(final String string) {
    props.keySet().findAll { final Object curKey ->
      string.contains((String) curKey)
    } as Set<String>
  }

  /**
   * Get the definition of a given key, with original formatting.
   * @param key Key to look for.
   * @return Key/value definition.
   */
  String getExpression(final String key) {
    final String value = props.get(key)
    if (value) {
      final Matcher matcher = content =~ /(?m)^(\s*${key}\s*[=:]\s*['"]?${value}['"]?\s*)$/
      return ((List<String>) matcher[0])[0]
    }
    return null
  }
}
