package com.github.benmanes.gradle.versions.version

import com.github.benmanes.gradle.versions.VersionsPluginExtension
import com.github.benmanes.gradle.versions.parser.BuildGradleGroovyParser
import com.github.benmanes.gradle.versions.parser.BuildGradleKotlinParser
import com.github.benmanes.gradle.versions.parser.BuildGradleParser
import com.github.benmanes.gradle.versions.parser.GradlePropertiesParser
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GFileUtils

@CompileStatic
class SetVersion extends GetVersion {

  protected final static String INFO_SKIPPED = 'Version update skipped by configuration.'
  protected final static String WARN_DO_NOT_SET_SAME_VERSION = 'Trying to set the same version already configured' +
    ', skipping task.'

  @TaskAction
  void run() {
    if (((VersionsPluginExtension) project.extensions.getByName('versions')).skipVersionUpdate) {
      logger.info(INFO_SKIPPED)
      return
    }
    if (getCurrentVersion() == getComputedVersion()) {
      logger.warn(WARN_DO_NOT_SET_SAME_VERSION)
      return
    }

    // Try and update version from project build file
    if (updateVersionFromBuildFile(project.getBuildFile())) {
      return
    }

    // Try and update version from a buildSrc plugin
    final File buildSrcRootFile = project.getRootProject().file('buildSrc')
    if (buildSrcRootFile.exists()) {
      logger.info('Looking in buildSrc for the version definition in a plugin.')
      final List<String> projectPlugins = project.getPlugins().collect { final Plugin plugin ->
        plugin.getClass().getName().replace('Plugin', '').toLowerCase()
      }

      final Collection<File> buildScripts = GFileUtils.listFiles(new File(buildSrcRootFile, 'src/main'),
        ['gradle', 'gradle.kts'] as String[], true)
      for (final File gradleFile : buildScripts) {
        if (projectPlugins.contains(gradleFile.getName().replaceAll('\\.gradle(\\.kts)?', ''))
          && updateVersionFromBuildFile(gradleFile)) {
          return
        }
      }
    }

    // Did not find the version anywhere ...
    throw new GradleException("Can not find the definition of current version '${getCurrentVersion()}'. " +
      'Activate info traces to see where it was searched.')
  }

  @TypeChecked(TypeCheckingMode.SKIP)
  private static BuildGradleParser getBuildGradleParser(final File buildGradleFile) {
    return buildGradleFile.getName().endsWith('.kts') ? new BuildGradleKotlinParser(buildGradleFile)
      : new BuildGradleGroovyParser(buildGradleFile)
  }

  private Boolean updateVersionFromBuildFile(final File buildFile) {
    Boolean found = false
    final BuildGradleParser buildGradleParser = getBuildGradleParser(buildFile)

    final String versionDefinition = buildGradleParser.getVersionDefinition()
    if (versionDefinition) {
      if (versionDefinition.contains(getCurrentVersion())) {
        logger.info("Version defined in file ${buildGradleParser.getFile().getPath()}, updating it.")
        updateVersionInFile(buildGradleParser)
        found = true
      } else if (project.tasks.size() > 1 && versionDefinition.contains(getComputedVersion())) {
        logger.info('Version already updated, probably in a previous task execution.')
        found = true
      } else {
        logger.info('Version not defined directly in build file, looking for a variable in gradle.properties files.')
        for (Project curProject = project; !found && curProject; curProject = curProject.getParent()) {
          found = updateVersionFromProperties(buildGradleParser, curProject.file('gradle.properties'))
        }
      }
    } else {
      logger.info("No version definition found in ${buildGradleParser.getFile().getPath()}.")
    }
    return found
  }

  private Boolean updateVersionFromProperties(final BuildGradleParser buildGradleParser,
                                              final File gradlePropertiesFile) {
    Boolean found = false
    if (gradlePropertiesFile.exists()) {
      logger.info("Searching version variable in ${gradlePropertiesFile.getPath()}")
      final GradlePropertiesParser propertiesParser = new GradlePropertiesParser(gradlePropertiesFile)
      // Loop in keys that are found in build.gradle version definition
      for (final String curKey : propertiesParser.getKeysContainedIn(buildGradleParser.getVersionDefinition())) {
        final String curValue = propertiesParser.props[curKey]
        if (curValue == getCurrentVersion()) {
          updateVersionInFile(propertiesParser.getFile(), propertiesParser.getExpression(curKey),
            propertiesParser.getContent())
          found = true
        } else if (project.tasks.size() > 1 && curValue == getComputedVersion()) {
          logger.info('Version already updated, probably in a previous task execution.')
          found = true
        }
      }
      logger.info("Could not find a variable matching project version in ${gradlePropertiesFile.getPath()}.")
    }
    return found
  }

  private void updateVersionInFile(final BuildGradleParser buildGradleParser) {
    updateVersionInFile(buildGradleParser.getFile(), buildGradleParser.getVersionExpression(),
      buildGradleParser.getContent())
  }

  private void updateVersionInFile(final File file, final String versionExpression,
                                   final String fileContent = file.getText()) {
    file.delete()
    file << fileContent.replace(
      versionExpression,
      versionExpression.replace(getCurrentVersion(), getComputedVersion()))
  }
}
