/* Copyright 2013 Kelly Robinson. All Rights Reserved. */
package com.github.benmanes.gradle.versions.analyze

import org.apache.maven.shared.dependency.analyzer.ClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DefaultClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DependencyAnalyzer
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis
import org.apache.maven.shared.dependency.analyzer.asm.ASMDependencyAnalyzer
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency

/**
 * Analyzes the dependencies of a project to determine which are:
 * used and declared; used and undeclared; unused and declared.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 * @author Kelly Robinson (krobinson@sonatype.com)
 */
class ProjectDependencyAnalyzer {
  private ClassAnalyzer classAnalyzer = new DefaultClassAnalyzer();
  private DependencyAnalyzer dependencyAnalyzer = new ASMDependencyAnalyzer();

  ProjectDependencyAnalysis analyzeDependencies(Project project) {
    if (!project.plugins.hasPlugin('java')) {
      throw new IllegalStateException("Project does not have the java plugin applied.")
    }
    Set<ResolvedDependency> firstLevelDeps = getFirstLevelDependencies(project, 'compile')
    Set<File> dependencyArtifacts = findModuleArtifactFiles(firstLevelDeps)

    Map<File, Set<String>> fileClassMap = buildArtifactClassMap(dependencyArtifacts)
    project.logger.info "fileClassMap = $fileClassMap"

    Set<String> dependencyClasses = analyzeClassDependencies(project)
    project.logger.info "dependencyClasses = $dependencyClasses"

    Set<File> usedArtifacts = buildUsedArtifacts(fileClassMap, dependencyClasses)
    project.logger.info "usedArtifacts = $usedArtifacts"

    Set<File> usedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
    usedDeclaredArtifacts.retainAll(usedArtifacts)
    project.logger.info "usedDeclaredArtifacts = $usedDeclaredArtifacts"

    Set<File> usedUndeclaredArtifacts = new LinkedHashSet<File>(usedArtifacts)
    usedUndeclaredArtifacts.removeAll(dependencyArtifacts)
    project.logger.info "usedUndeclaredArtifacts = $usedUndeclaredArtifacts"

    Set<String> unusedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
    unusedDeclaredArtifacts.removeAll(usedArtifacts)
    project.logger.info "unusedDeclaredArtifacts = $unusedDeclaredArtifacts"

    // Now work back from the files to the artifact information
    ConfigurationContainer configurations = project.configurations

    def nonTestConfigurations = configurations.findAll { !it.name.contains('test') }
    List<ResolvedArtifact> artifacts = nonTestConfigurations*.resolvedConfiguration*.resolvedArtifacts.unique {it.resolvedDependency}.flatten()
    return new ProjectDependencyAnalysis(
      artifacts.findAll { ResolvedArtifact artifact -> artifact.file in usedDeclaredArtifacts }.unique {it.file} as Set,
      artifacts.findAll { ResolvedArtifact artifact -> artifact.file in usedUndeclaredArtifacts }.unique {it.file} as Set,
      artifacts.findAll { ResolvedArtifact artifact -> artifact.file in unusedDeclaredArtifacts }.unique {it.file} as Set)
  }

  private Set<ResolvedDependency> getFirstLevelDependencies(Project project, String configurationName) {
    project.configurations."$configurationName".resolvedConfiguration.firstLevelModuleDependencies
  }

  /**
   * Map each of the files declared on all configurations of the project to a collection of the
   * class names they contain.
   *
   * @param project the project we're working on
   * @return a Map of files to their classes
   * @throws IOException
   */
  private Map<File, Set<String>> buildArtifactClassMap(Set<File> dependencyArtifacts)
      throws IOException {
    Map<File, Set<String>> artifactClassMap = [:]

    dependencyArtifacts.each { File file ->
      if (file.name.endsWith('jar')) {
        artifactClassMap.put(file, classAnalyzer.analyze(file.toURL()))
      }
      else {
        project.logger.info "Skipping analysis of file for classes: $file"
      }
    }
    return artifactClassMap
  }

  private Set<File> findModuleArtifactFiles(Set<ResolvedDependency> dependencies) {
    dependencies*.moduleArtifacts*.collectMany {it.file}.unique()
  }

  /**
   * Find and analyze all class files to determine which external classes are used.
   *
   * @param project
   * @return a Set of class names
   */
  private Collection analyzeClassDependencies(Project project) {
    return project.sourceSets*.output.classesDir?.collectMany { File file ->
      dependencyAnalyzer.analyze(file.toURI().toURL())
    }?.unique()
  }

  /**
   * Determine which of the project dependencies are used.
   *
   * @param artifactClassMap a map of Files to the classes they contain
   * @param dependencyClasses all classes used directly by the project
   * @return a set of project dependencies confirmed to be used by the project
   */
  private Set<File> buildUsedArtifacts(
      Map<File, Set<String>> artifactClassMap, Set<String> dependencyClasses) {
    dependencyClasses.collectMany { String className ->
      artifactClassMap.findAll { file, classNames ->
        classNames.contains(className)
      }.keySet()
    } as Set
  }
}
