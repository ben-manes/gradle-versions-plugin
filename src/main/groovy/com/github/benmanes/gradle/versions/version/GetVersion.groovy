package com.github.benmanes.gradle.versions.version

import com.github.benmanes.gradle.versions.VersionsPluginExtension
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

import java.util.regex.Matcher

@CompileStatic
class GetVersion extends DefaultTask {

  protected final static String HYPHEN = '-'
  protected final static String SNAPSHOT = 'SNAPSHOT'

  protected final static String OPT_DEFAULT_SUFFIX = 'defaultSuffix'
  protected final static String OPT_NEW_VERSION = 'new-version'
  protected final static String OPT_SUFFIX = 'suffix'
  protected final static String OPT_NO_SUFFIX = 'no-suffix'
  protected final static String OPT_INCREMENT = 'increment'
  protected final static String OPT_INCREMENT_MAJOR = 'major'
  protected final static String OPT_INCREMENT_MINOR = 'minor'
  protected final static String OPT_INCREMENT_TECHNICAL = 'technical'
  protected final static String ERROR_SUFFIX_AND_NOSUFFIX = "Options '$OPT_SUFFIX' and '$OPT_NO_SUFFIX' are " +
    'mutually exclusive, you can use only one of those.'
  protected final static String ERROR_NEWVERSION_AND_SUFFIX_OR_NOSUFFIX = "Options '$OPT_NEW_VERSION', " +
    "'$OPT_SUFFIX' and '$OPT_NO_SUFFIX' are mutually exclusive, you can use only one of those."
  protected final static String ERROR_INCREMENT_POSITION_OUT_OF_BOUND = 'Increment position (%s) is greater than the ' +
    'numbers of digits in the version (%s).'
  protected final static String ERROR_UNKNOWN_INCREMENT = "Value of option '$OPT_INCREMENT' must be a digit, " +
    "'$OPT_INCREMENT_MAJOR', '$OPT_INCREMENT_MINOR', or '$OPT_INCREMENT_TECHNICAL'."
  protected final static String WARN_UNSPECIFIED_VERSION = 'Version is undefined, can not apply any change onto it.'

  protected String newVersion = System.properties[OPT_NEW_VERSION]

  @Input
  @Optional
  String getNewVersion() {
    return newVersion
  }

  @Option(option = OPT_NEW_VERSION, description = 'Specify the new version for scratch')
  void setNewVersion(final String newVersion) {
    this.newVersion = newVersion
  }

  protected String suffix = System.properties[OPT_SUFFIX]

  @Input
  @Optional
  String getSuffix() {
    return suffix
  }

  @Option(option = OPT_SUFFIX,
    description = 'Append or replace a suffix to the version. Can be a string, true to append the default suffix or false to trim any existing suffix.')
  void setSuffix(final String suffix) {
    this.suffix = suffix
  }

  protected Boolean noSuffix = System.properties[OPT_NO_SUFFIX]

  @Input
  @Optional
  Boolean getNoSuffix() {
    return noSuffix
  }

  @Option(option = OPT_NO_SUFFIX, description = 'Remove existing suffix if any.')
  void setNoSuffix(final Boolean noSuffix) {
    this.noSuffix = noSuffix
  }

  protected String increment = System.properties[OPT_INCREMENT]

  @Input
  @Optional
  String getIncrement() {
    return increment
  }

  @Option(option = OPT_INCREMENT,
    description = 'Position of a version digit to increment, starting at 1, from left to right. Following digits will be set to 0. Aliases can be used : \'major\' for the first digit, \'minor\' for the second, \'technical\' for the third.')
  void setIncrement(final String increment) {
    this.increment = increment
  }

  @Internal
  String currentVersion

  @Internal
  String computedVersion

  GetVersion() {
    description = 'Get current version eventually modified by incrementation and/or suffix addition or removal.'
    group = 'Help'
  }

  @TaskAction
  void run() {
    logger.quiet(getComputedVersion())
  }

  String getCurrentVersion() {
    if (!currentVersion) {
      currentVersion = ((String) project.getVersion()).trim()
    }
    return currentVersion
  }

  /**
   * Compute version following given options.
   * @return Computed version.
   */
  String getComputedVersion() {
    if (!computedVersion) {
      checkOptions()

      // Get new version
      if (getCurrentVersion() == project.DEFAULT_VERSION) { // No version defined in the project
        logger.warn(WARN_UNSPECIFIED_VERSION)
        computedVersion = getCurrentVersion()
      } else if (newVersion) { // A new version is provided
        computedVersion = newVersion
      } else { // Computing version based on task options
        // Parse current version into prefix, baseVersion and suffix
        final Tuple3 versionElements = parseVersion(getCurrentVersion())

        // Increment base version if needed
        final String baseVersion = incrementVersion((String) versionElements.getSecond())

        // Append/remove a suffix if needed
        String newSuffix = versionElements.getThird()
        if (noSuffix) {
          newSuffix = ''
        } else if (getSuffix()) {
          newSuffix = HYPHEN + getSuffix()
        }

        // Build new version
        computedVersion = ((String) versionElements.getFirst() ?: '') + baseVersion + (newSuffix ?: '')
      }
    }
    return computedVersion
  }

  @TypeChecked(TypeCheckingMode.SKIP)
  protected static Tuple3 parseVersion(final String version) {
    final Matcher baseVersionMatcher = version =~ /[a-zA-Z0-1\/_-]*((\d+.)+\d+)[a-zA-Z0-1\/_-]*/
    String baseVersion = baseVersionMatcher[0][1]
    final Matcher prefixMatcher = version =~ /([a-zA-Z0-1\/_-]*)$baseVersion.*/
    String prefix = prefixMatcher[0][1]
    final Matcher suffixMatcher = version =~ /.*$baseVersion([a-zA-Z0-1\/_-]*)/
    String suffix = suffixMatcher[0][1]
    return new Tuple3(prefix, baseVersion, suffix)
  }

  protected void checkOptions() {
    if (suffix) {
      switch (suffix.toLowerCase()) {
        case 'true':
          suffix = ((VersionsPluginExtension) project.extensions.getByName('versions')).defaultSuffix
          break
        case 'false':
          noSuffix = true
          suffix = null
          break
        default:
          // nothing to do
          break
      }
    }

    if (suffix && noSuffix) {
      throw new InvalidUserDataException(ERROR_SUFFIX_AND_NOSUFFIX)
    } else if (newVersion && (suffix || noSuffix)) {
      throw new InvalidUserDataException(ERROR_NEWVERSION_AND_SUFFIX_OR_NOSUFFIX)
    }
  }

  /**
   * Apply increments options to {@code version}.
   * @param version Version to increment.
   * @return Incremented version.
   */
  protected String incrementVersion(final String version) {
    // Parse increment option
    Integer incrementPosition
    switch (increment) {
      case null:
      case '':
        return version
      case OPT_INCREMENT_MAJOR:
        incrementPosition = 1
        break
      case OPT_INCREMENT_MINOR:
        incrementPosition = 2
        break
      case OPT_INCREMENT_TECHNICAL:
        incrementPosition = 3
        break
      case ~/^[0-9]+$/:
        incrementPosition = increment.toInteger()
        break
      default:
        throw new InvalidUserDataException(ERROR_UNKNOWN_INCREMENT)
    }

    // Split numbers
    String[] versionComponents = version.split('\\.')
    if (incrementPosition > versionComponents.size()) {
      throw new InvalidUserDataException(
        String.format(ERROR_INCREMENT_POSITION_OUT_OF_BOUND, incrementPosition, versionComponents.size()))
    }

    // Increment the number
    String number = versionComponents[incrementPosition - 1]
    int numberInt = Integer.valueOf(number)
    versionComponents[incrementPosition - 1] = ++numberInt

    // Concatenate the version string
    String newVersion = ''
    int i = 0
    for (; i < incrementPosition; i++) {
      newVersion += versionComponents[i] + '.'
    }
    for (; i < versionComponents.length; i++) {
      newVersion += '0.'
    }
    return newVersion[0..-2]
  }
}
