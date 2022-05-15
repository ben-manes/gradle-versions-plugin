package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.util.XmlSlurper
import groovy.util.slurpersupport.GPathResult
import groovy.util.slurpersupport.NodeChildren
import org.codehaus.groovy.runtime.DefaultGroovyMethods.asBoolean
import org.codehaus.groovy.runtime.DefaultGroovyMethods.getMetaClass
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.HasConfigurableAttributes
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.specs.Specs.SATISFIES_ALL
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the configuration to determine the version status of its dependencies.
 */
class Resolver(
  private val project: Project,
  private val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  private val checkConstraints: Boolean
) {
  private var projectUrls = ConcurrentHashMap<ModuleVersionIdentifier, ProjectUrl>()

  init {
    logRepositories()
  }

  /** Returns the version status of the configuration's dependencies at the given revision. */
  fun resolve(configuration: Configuration, revision: String): Set<DependencyStatus> {
    val coordinates = getCurrentCoordinates(configuration)
    val latestConfiguration = createLatestConfiguration(configuration, revision, coordinates)
    val lenient = latestConfiguration.resolvedConfiguration.lenientConfiguration
    val resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    val unresolved = lenient.unresolvedModuleDependencies
    return getStatus(coordinates, resolved, unresolved)
  }

  /** Returns the version status of the configuration's dependencies. */
  private fun getStatus(
    coordinates: Map<Coordinate.Key, Coordinate>,
    resolved: Set<ResolvedDependency>,
    unresolved: Set<UnresolvedDependency>
  ): Set<DependencyStatus> {
    val result = hashSetOf<DependencyStatus>()
    for (dependency in resolved) {
      val resolvedCoordinate = Coordinate.from(dependency.module.id)
      val originalCoordinate = coordinates[resolvedCoordinate.key]
      val coord = originalCoordinate ?: resolvedCoordinate
      if (originalCoordinate == null && resolvedCoordinate.groupId != "null") {
        project.logger.info("Skipping hidden dependency: $resolvedCoordinate")
      } else {
        val projectUrl = getProjectUrl(dependency.module.id)
        result.add(DependencyStatus(coord, resolvedCoordinate.version, projectUrl))
      }
    }

    for (dependency in unresolved) {
      val resolvedCoordinate = Coordinate.from(dependency.selector)
      val originalCoordinate = coordinates[resolvedCoordinate.key]
      val coord = originalCoordinate ?: resolvedCoordinate
      result.add(DependencyStatus(coord, dependency))
    }
    return result
  }

  /** Returns a copy of the configuration where dependencies will be resolved up to the revision.  */
  private fun createLatestConfiguration(
    configuration: Configuration,
    revision: String,
    currentCoordinates: Map<Coordinate.Key, Coordinate>
  ): Configuration {
    val latest = configuration.dependencies
      .filter { dependency -> dependency is ExternalDependency }
      .map { dependency ->
        createQueryDependency(dependency as ModuleDependency)
      } as MutableList<Dependency>

    // Common use case for dependency constraints is a java-platform BOM project or to control
    // version of transitive dependency.
    if (supportsConstraints(configuration)) {
      for (dependency in configuration.dependencyConstraints) {
        latest.add(createQueryDependency(dependency))
      }
    }

    val copy = configuration.copyRecursive().setTransitive(false)
    // https://github.com/ben-manes/gradle-versions-plugin/issues/127
    if (asBoolean(
        getMetaClass(copy)
          .respondsTo(copy, "setCanBeResolved", arrayOf<Any>(Boolean::class.java))
      )
    ) {
      copy.isCanBeResolved = true
    }

    // https://github.com/ben-manes/gradle-versions-plugin/issues/592
    // allow resolution of dynamic latest versions regardless of the original strategy
    if (asBoolean(
        getMetaClass(copy.resolutionStrategy)
          .hasProperty(copy.resolutionStrategy, "failOnDynamicVersions")
      )
    ) {
      getMetaClass(copy.resolutionStrategy)
        .setProperty(copy.resolutionStrategy, "failOnDynamicVersions", false)
    }

    // Resolve using the latest version of explicitly declared dependencies and retains Kotlin's
    // inherited stdlib dependencies from the super configurations. This is required for variant
    // resolution, but the full set can break consumer capability matching.
    val inherited = configuration.allDependencies
      .filter { dependency -> dependency is ExternalDependency }
      .filter { dependency -> dependency.group == "org.jetbrains.kotlin" }
      .filter { dependency -> dependency.version != null } -
      configuration.dependencies

    // Adds the Kotlin 1.2.x legacy metadata to assist in variant selection
    val metadata = project.configurations.findByName("commonMainMetadataElements")
    if (metadata == null) {
      val compile = project.configurations.findByName("compile")
      if (compile != null) {
        addAttributes(copy, compile) { key -> key.contains("kotlin") }
      }
    } else {
      addAttributes(copy, metadata)
    }

    copy.dependencies.clear()
    copy.dependencies.addAll(latest)
    copy.dependencies.addAll(inherited)

    addRevisionFilter(copy, revision)
    addAttributes(copy, configuration)
    addCustomResolutionStrategy(copy, currentCoordinates)
    return copy
  }

  /** Returns a variant of the provided dependency used for querying the latest version.  */
  private fun createQueryDependency(dependency: ModuleDependency): Dependency {
    // If no version was specified then it may be intended to be resolved by another plugin
    // (e.g. the dependency-management-plugin for BOMs) or is an explicit file (e.g. libs/*.jar).
    // In the case of another plugin we use "+" in the hope that the plugin will not restrict the
    // query (see issue #97). Otherwise if its a file then use "none" to pass it through.
    val version = if (dependency.version == null) {
      if (dependency.artifacts.isEmpty()) {
        "+"
      } else {
        "none"
      }
    } else {
      "+"
    }

    // Format the query with an optional classifier and extension
    var query = "${dependency.group}:${dependency.name}:$version"
    if (dependency.artifacts.isNotEmpty()) {
      dependency.artifacts.firstOrNull()?.classifier?.let { classifier ->
        query += ":$classifier"
      }
      dependency.artifacts.firstOrNull()?.extension?.let { extension ->
        query += "@$extension"
      }
    }
    val latest = project.dependencies.create(query) as ModuleDependency
    latest.isTransitive = false

    // Copy selection qualifiers if the artifact was not explicitly set
    if (dependency.artifacts.isEmpty()) {
      addAttributes(latest, dependency)
    }
    return latest
  }

  /** Returns a variant of the provided dependency used for querying the latest version.  */
  private fun createQueryDependency(dependency: DependencyConstraint): Dependency {
    // If no version was specified then use "none" to pass it through.
    val version = if (dependency.version == null) "none" else "+"
    val nonTransitiveDependency =
      project.dependencies.create("${dependency.group}:${dependency.name}:$version") as ModuleDependency
    nonTransitiveDependency.isTransitive = false
    return nonTransitiveDependency
  }

  /** Adds the attributes from the source to the target. */
  private fun addAttributes(
    target: HasConfigurableAttributes<*>,
    source: HasConfigurableAttributes<*>,
    filter: (String) -> Boolean = { key: String -> true },
  ) {
    target.attributes { container ->
      for (key in source.attributes.keySet()) {
        if (filter.invoke(key.name)) {
          val value = source.attributes.getAttribute(key as Attribute<Any>)
          container.attribute(key, value)
        }
      }
    }
  }

  /** Adds a revision filter by rejecting candidates using a component selection rule.  */
  private fun addRevisionFilter(configuration: Configuration, revision: String) {
    configuration.resolutionStrategy { componentSelection ->
      componentSelection.componentSelection { rules ->
        val revisionFilter = { selection: ComponentSelection, metadata: ComponentMetadata? ->
          val accepted = (metadata == null) ||
            ((revision == "release") && (metadata.status == "release")) ||
            ((revision == "milestone") && (metadata.status != "integration")) ||
            (revision == "integration") || (selection.candidate.version == "none")
          if (!accepted) {
            selection.reject("Component status ${metadata?.status} rejected by revision $revision")
          }
        }
        rules.all { selectionAction ->
          if (ComponentSelection::class.members.any { it.name == "getMetadata" }) {
            revisionFilter(selectionAction, selectionAction.metadata)
          } else {
            revisionFilter
          }
        }
      }
    }
  }

  /** Adds a custom resolution strategy only applicable for the dependency updates task.  */
  private fun addCustomResolutionStrategy(
    configuration: Configuration,
    currentCoordinates: Map<Coordinate.Key, Coordinate>
  ) {
    configuration.resolutionStrategy { inner ->
      resolutionStrategy?.execute(ResolutionStrategyWithCurrent(inner, currentCoordinates))
    }
  }

  /** Returns the coordinates for the current (declared) dependency versions. */
  private fun getCurrentCoordinates(configuration: Configuration): Map<Coordinate.Key, Coordinate> {
    val declared = getResolvableDependencies(configuration)
      .associateBy({ it.key }, { it })
    if (declared.isEmpty()) {
      return emptyMap()
    }

    // https://github.com/ben-manes/gradle-versions-plugin/issues/231
    val transitive = declared.values.any { it.version == "none" }

    val coordinates = hashMapOf<Coordinate.Key, Coordinate>()
    val copy = configuration.copyRecursive().setTransitive(transitive)
    // https://github.com/ben-manes/gradle-versions-plugin/issues/127
    if (asBoolean(
        getMetaClass(copy)
          .respondsTo(copy, "setCanBeResolved", arrayOf<Any>(Boolean::class.java))
      )
    ) {
      copy.isCanBeResolved = true
    }
    val lenient = copy.resolvedConfiguration.lenientConfiguration

    val resolved = lenient.getFirstLevelModuleDependencies(SATISFIES_ALL)
    for (dependency in resolved) {
      val coordinate = Coordinate.from(dependency.module.id, declared)
      coordinates[coordinate.key] = coordinate
    }

    val unresolved = lenient.unresolvedModuleDependencies
    for (dependency in unresolved) {
      val key = Coordinate.keyFrom(dependency.selector)
      declared[key]?.let { coordinates.put(key, it) }
    }

    if (supportsConstraints(copy)) {
      for (constraint in copy.dependencyConstraints) {
        val coordinate = Coordinate.from(constraint)
        // Only add a constraint to the report if there is no dependency matching it, this means it
        // is targeting a transitive dependency or is part of a platform.
        if (!coordinates.containsKey(coordinate.key)) {
          declared[coordinate.key]?.let { coordinates.put(coordinate.key, it) }
        }
      }
    }

    // Ignore undeclared (hidden) dependencies that appear when resolving a configuration
    coordinates.keys.retainAll(declared.keys)

    return coordinates
  }

  private fun logRepositories() {
    val root = project.rootProject == project
    val label = "${
    if (root) {
      project.name
    } else {
      project.path
    }
    } project ${
    if (root) {
      " (root)"
    } else {
      ""
    }
    }"
    if (!project.buildscript.configurations
      .flatMap { config -> config.dependencies }
      .any()
    ) {
      project.logger.info("Resolving $label buildscript with repositories:")
      for (repository in project.buildscript.repositories) {
        logRepository(repository)
      }
    }
    project.logger.info("Resolving $label configurations with repositories:")
    for (repository in project.repositories) {
      logRepository(repository)
    }
  }

  private fun logRepository(repository: ArtifactRepository) {
    when (repository) {
      is FlatDirectoryArtifactRepository -> {
        project.logger.info(" - ${repository.name}: ${repository.dirs}")
      }
      is IvyArtifactRepository -> {
        project.logger.info(" - ${repository.name}: ${repository.url}")
      }
      is MavenArtifactRepository -> {
        project.logger.info(" - ${repository.name}: ${repository.url}")
      }
      else -> {
        project.logger.info(" - ${repository.name}: ${repository.javaClass.simpleName}")
      }
    }
  }

  private fun getProjectUrl(id: ModuleVersionIdentifier): String? {
    if (project.gradle.startParameter.isOffline) {
      return null
    }
    var projectUrl = ProjectUrl()
    val cached = projectUrls.putIfAbsent(id, projectUrl)
    if (cached != null) {
      projectUrl = cached
    }
    synchronized(projectUrl) {
      if (!projectUrl.resolved) {
        projectUrl.resolved = true
        projectUrl.url = resolveProjectUrl(id)
      }
      return projectUrl.url
    }
  }

  private fun resolveProjectUrl(id: ModuleVersionIdentifier): String? {
    return try {
      val resolutionResult = project.dependencies
        .createArtifactResolutionQuery()
        .forComponents(DefaultModuleComponentIdentifier.newId(id))
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
        .execute()

      // size is 0 for gradle plugins, 1 for normal dependencies
      for (result in resolutionResult.resolvedComponents) {
        // size should always be 1
        for (artifact in result.getArtifacts(MavenPomArtifact::class.java)) {
          if (artifact is ResolvedArtifactResult) {
            val file = artifact.file
            project.logger.info("Pom file for $id is $file")
            var url = getUrlFromPom(file)
            if (!url.isNullOrEmpty()) {
              project.logger.info("Found url for $id: $url")
              return url.trim()
            } else {
              val parent = getParentFromPom(file)
              if (parent != null &&
                "${parent.group}:${parent.name}" != "org.sonatype.oss:oss-parent"
              ) {
                url = getProjectUrl(parent)
                if (!url.isNullOrEmpty()) {
                  return url.trim()
                }
              }
            }
          }
        }
      }
      project.logger.info("Did not find url for $id")
      null
    } catch (e: Exception) {
      project.logger.info("Failed to resolve the project's url", e)
      null
    }
  }

  private fun supportsConstraints(configuration: Configuration): Boolean {
    return checkConstraints && !getMetaClass(configuration)
      .respondsTo(configuration, "getDependencyConstraints").isNullOrEmpty()
  }

  private fun getResolvableDependencies(configuration: Configuration): List<Coordinate> {
    val coordinates = configuration.dependencies
      .filter { dependency -> dependency is ExternalDependency }
      .map { dependency ->
        Coordinate.from(dependency)
      } as MutableList<Coordinate>

    if (supportsConstraints(configuration)) {
      configuration.dependencyConstraints.forEach { dependencyConstraint ->
        coordinates.add(Coordinate.from(dependencyConstraint))
      }
    }
    return coordinates
  }

  companion object {
    private fun getUrlFromPom(file: File): String? {
      val pom = XmlSlurper(false, false).parse(file)
      val url = (pom.getProperty("url") as NodeChildren?)?.text()
      return url
        ?: ((pom.getProperty("scm") as NodeChildren?)?.getProperty("url") as NodeChildren?)?.text()
    }

    private fun getParentFromPom(file: File): ModuleVersionIdentifier? {
      val pom = XmlSlurper(false, false).parse(file)
      val parent: GPathResult? = pom.getProperty("parent") as NodeChildren?
      if (parent != null) {
        val groupId = (parent.getProperty("groupId") as NodeChildren?)?.text()
        val artifactId = (parent.getProperty("artifactId") as NodeChildren?)?.text()
        val version = (parent.getProperty("version") as NodeChildren?)?.text()
        if (groupId != null && artifactId != null && version != null) {
          return DefaultModuleVersionIdentifier.newId(groupId, artifactId, version)
        }
      }
      return null
    }

    class ProjectUrl {
      var resolved: Boolean = false
      var url: String? = null
    }
  }
}
