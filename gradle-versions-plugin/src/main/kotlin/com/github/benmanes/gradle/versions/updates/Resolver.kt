package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.NodeChildren
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
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.HasConfigurableAttributes
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependencyConstraint
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
  private val checkConstraints: Boolean,
) {
  private var projectUrls = ConcurrentHashMap<ModuleVersionIdentifier, ProjectUrl>()

  // The platform declarations whose scan threw, so a configuration inheriting the same ones does
  // not repeat a resolution already known to fail. Only a failure is shared: what a scan finds
  // depends on the resolving configuration's own attributes, constraints and resolution strategy,
  // while skipping one costs an attribution at most.
  private val failedPlatformScans = hashSetOf<Set<ModuleDependency>>()

  init {
    logRepositories()
  }

  /** Returns the declared dependency keys of the configuration, before lazy actions contribute. */
  fun declaredKeys(configuration: Configuration): Set<Coordinate.Key> =
    getResolvableDependencies(configuration).mapTo(hashSetOf()) { it.key }

  /** Returns the version status of the configuration's dependencies at the given revision. */
  @JvmOverloads
  fun resolve(
    configuration: Configuration,
    revision: String,
    declaredKeys: Set<Coordinate.Key> = declaredKeys(configuration),
  ): Set<DependencyStatus> = resolve(configuration, revision) { declaredKeys }

  /**
   * Returns the version status of the configuration's dependencies at the given revision, where the
   * keys the build declared are supplied once the lazy actions that contribute the rest have run.
   */
  fun resolve(
    configuration: Configuration,
    revision: String,
    declaredKeys: () -> Set<Coordinate.Key>,
  ): Set<DependencyStatus> = resolve(configuration, revision, nameDeclaringConfiguration = false, scriptClasspath = false, declaredKeys)

  /**
   * Returns the version status as above, naming the configuration of a dependency declared directly
   * against it when asked, which the buildscript's configurations are resolved without, and marking
   * the coordinates of a script classpath, the sole route by which a plugin marker enters a build.
   */
  internal fun resolve(
    configuration: Configuration,
    revision: String,
    nameDeclaringConfiguration: Boolean,
    scriptClasspath: Boolean,
    declaredKeys: () -> Set<Coordinate.Key>,
  ): Set<DependencyStatus> {
    // Runs the actions that contribute dependencies lazily, so that the declared set below is read
    // after them rather than before. A contribution missing from that set is discarded as an
    // undeclared dependency, or reported with the resolved version as its declared one.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/987
    configuration.incoming.dependencies

    val current = getCurrentCoordinates(configuration, declaredKeys(), nameDeclaringConfiguration, scriptClasspath)
    val latestConfiguration = createLatestConfiguration(configuration, revision, current)
    val root = latestConfiguration.incoming.resolutionResult.root
    return getStatus(current, root)
  }

  /** Returns the version status of the configuration's dependencies. */
  private fun getStatus(
    current: CurrentCoordinates,
    root: ResolvedComponentResult,
  ): Set<DependencyStatus> {
    val coordinates = current.coordinates
    val result = hashSetOf<DependencyStatus>()
    for (dependency in root.dependencies) {
      // A constraint has no resolved version to report against.
      if (dependency.isConstraint) {
        continue
      }
      when (dependency) {
        is ResolvedDependencyResult -> {
          val moduleVersion = dependency.selected.moduleVersion ?: continue
          val resolvedCoordinate = Coordinate.from(moduleVersion)
          val originalCoordinate = coordinates[resolvedCoordinate.key]
          val coord = originalCoordinate ?: resolvedCoordinate
          val projectUrl = getProjectUrl(moduleVersion)
          val contributed = coord.key in current.contributedKeys
          val configurations = current.contributedConfigurations[coord.key].orEmpty()
          result.add(
            DependencyStatus(coord, resolvedCoordinate.version, projectUrl, contributed, configurations),
          )
        }
        is UnresolvedDependencyResult -> {
          val selector = dependency.attempted as? ModuleComponentSelector ?: continue
          val resolvedCoordinate = Coordinate.from(selector)
          val originalCoordinate = coordinates[resolvedCoordinate.key]
          val coord = originalCoordinate ?: resolvedCoordinate
          val contributed = coord.key in current.contributedKeys
          val configurations = current.contributedConfigurations[coord.key].orEmpty()
          result.add(DependencyStatus(coord, dependency, contributed, configurations))
        }
      }
    }
    return result
  }

  /** Returns a copy of the configuration where dependencies will be resolved up to the revision.  */
  private fun createLatestConfiguration(
    configuration: Configuration,
    revision: String,
    current: CurrentCoordinates,
  ): Configuration {
    val latest =
      configuration.allDependencies
        .filterIsInstance<ExternalDependency>()
        .mapTo(mutableListOf()) { dependency ->
          createQueryDependency(dependency as ModuleDependency, current.substitutions)
        }

    // Common use case for dependency constraints is a java-platform BOM project or to control
    // version of transitive dependency.
    if (supportsConstraints(configuration)) {
      for (dependency in configuration.allDependencyConstraints) {
        if (dependency !is DefaultProjectDependencyConstraint) {
          latest.add(createQueryDependency(dependency))
        }
      }
    }

    for (source in current.platformSources) {
      latest.add(createPlatformQueryDependency(source))
    }

    val copy = configuration.copyRecursive().setTransitive(false)

    // https://github.com/ben-manes/gradle-versions-plugin/issues/592
    // allow resolution of dynamic latest versions regardless of the original strategy
    if (asBoolean(
        getMetaClass(copy.resolutionStrategy)
          .hasProperty(copy.resolutionStrategy, "failOnDynamicVersions"),
      )
    ) {
      getMetaClass(copy.resolutionStrategy)
        .setProperty(copy.resolutionStrategy, "failOnDynamicVersions", false)
    }

    // Resolve using the latest version of explicitly declared dependencies and retains Kotlin's
    // inherited dependencies (importantly, including stdlib) from the super configurations. This
    // is required for variant resolution, but the full set can break consumer capability matching.
    val isKotlinDep = { dependency: ExternalDependency -> (dependency.group?.startsWith("org.jetbrains.kotlin") ?: false) }
    val inheritedKotlin =
      configuration.allDependencies
        .filterIsInstance<ExternalDependency>()
        .filter { d -> isKotlinDep(d) }
        .minus(configuration.dependencies)

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
    copy.dependencies.addAll(inheritedKotlin)

    // https://github.com/ben-manes/gradle-versions-plugin/issues/802
    // The copy inherits the original constraints, which would reject the dynamic query versions.
    // They are queried separately above as added dependencies.
    copy.dependencyConstraints.clear()

    // https://github.com/ben-manes/gradle-versions-plugin/issues/781
    // The copy inherits activated dependency locking but has no lock state of its own.
    copy.resolutionStrategy.deactivateDependencyLocking()

    addRevisionFilter(copy, revision, current.coordinates)
    addAttributes(copy, configuration)
    addCustomResolutionStrategy(copy, current.coordinates)

    disableAutoTargetJvm(copy)
    return copy
  }

  /** Returns a variant of the provided dependency used for querying the latest version.  */
  private fun createQueryDependency(
    dependency: ModuleDependency,
    substitutions: Map<Coordinate.Key, Coordinate.Key>,
  ): Dependency {
    // If no version was specified then it may be intended to be resolved by another plugin
    // (e.g. the dependency-management-plugin for BOMs) or is an explicit file (e.g. libs/*.jar).
    // In the case of another plugin we use "+" in the hope that the plugin will not restrict the
    // query (see issue #97). Otherwise, if it's a file then use "none" to pass it through.
    val version =
      if (dependency.version == null) {
        if (dependency.artifacts.isEmpty()) {
          "+"
        } else {
          "none"
        }
      } else {
        "+"
      }

    // A rule that substitutes another module for this one applies to the query as well, which would
    // pin the latest version to the substitute. Ask for the substituted module instead, so
    // that the rule does not match and the query is answered for what the build actually resolves.
    val substitute = substitutions[Coordinate.from(dependency as Dependency).key]

    // Format the query with an optional classifier and extension
    var query =
      "${substitute?.groupId ?: dependency.group.orEmpty()}:${substitute?.artifactId ?: dependency.name}:$version"
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

  /** Returns a platform dependency used for querying the latest version of a consumed platform. */
  private fun createPlatformQueryDependency(coordinate: Coordinate): Dependency {
    val dependency =
      project.dependencies.create("${coordinate.groupId}:${coordinate.artifactId}:+") as ModuleDependency
    dependency.isTransitive = false
    dependency.attributes { attributes ->
      attributes.attribute(
        Category.CATEGORY_ATTRIBUTE,
        project.objects.named(Category::class.java, Category.REGULAR_PLATFORM),
      )
    }
    return dependency
  }

  /** Returns a variant of the provided dependency used for querying the latest version.  */
  private fun createQueryDependency(dependency: DependencyConstraint): Dependency {
    // If no version was specified then use "none" to pass it through.
    val version = if (dependency.version == null) "none" else "+"
    val nonTransitiveDependency =
      project.dependencies.create("${dependency.group.orEmpty()}:${dependency.name}:$version") as ModuleDependency
    nonTransitiveDependency.isTransitive = false
    return nonTransitiveDependency
  }

  private fun disableAutoTargetJvm(configuration: Configuration) {
    // Disable the auto target jvm inherited from the copied configuration
    // https://github.com/ben-manes/gradle-versions-plugin/issues/727#issuecomment-1427132589
    // Only override an inherited value: injecting the attribute where no jvm plugin registered its
    // schema rules makes the request uninterpretable and fails variant selection.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/746
    if (configuration.attributes.contains(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE)) {
      configuration.attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, Integer.MAX_VALUE)
    }
  }

  /** Adds the attributes from the source to the target. */
  private fun addAttributes(
    target: HasConfigurableAttributes<*>,
    source: HasConfigurableAttributes<*>,
    filter: (String) -> Boolean = { _ -> true },
  ) {
    target.attributes { container ->
      for (key in source.attributes.keySet()) {
        if (filter.invoke(key.name)) {
          @Suppress("UNCHECKED_CAST")
          val value = source.attributes.getAttribute(key as Attribute<Any>)!!
          container.attribute(key, value)
        }
      }
    }
  }

  /** Adds a revision filter by rejecting candidates using a component selection rule.  */
  private fun addRevisionFilter(
    configuration: Configuration,
    revision: String,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
  ) {
    configuration.resolutionStrategy { componentSelection ->
      componentSelection.componentSelection { rules ->
        val revisionFilter = { selection: ComponentSelection, metadata: ComponentMetadata? ->
          // A module published only as a snapshot has no candidate that a milestone or release
          // revision accepts, so the version the build already uses is exempt from the check.
          // https://github.com/ben-manes/gradle-versions-plugin/issues/475
          val candidateCoordinate = Coordinate.from(selection.candidate)
          val isCurrent =
            currentCoordinates[candidateCoordinate.key]?.version == candidateCoordinate.version
          val accepted =
            (metadata == null) ||
              ((revision == "release") && (metadata.status == "release")) ||
              ((revision == "milestone") && (metadata.status != "integration")) ||
              (revision == "integration") || (selection.candidate.version == "none") ||
              isCurrent
          if (!accepted) {
            selection.reject("Component status ${metadata?.status} rejected by revision $revision")
          }
        }
        rules.all { selectionAction ->
          if (ComponentSelection::class.members.any { it.name == "getMetadata" }) {
            revisionFilter(selectionAction, selectionAction.metadata)
          }
        }
      }
    }
  }

  /** Adds a custom resolution strategy only applicable for the dependency updates task.  */
  private fun addCustomResolutionStrategy(
    configuration: Configuration,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
  ) {
    configuration.resolutionStrategy { inner ->
      resolutionStrategy?.execute(ResolutionStrategyWithCurrent(inner, currentCoordinates))
    }
  }

  /** Returns the coordinates for the current (declared) dependency versions. */
  private fun getCurrentCoordinates(
    configuration: Configuration,
    declaredBeforeActions: Set<Coordinate.Key>,
    nameDeclaringConfiguration: Boolean,
    scriptClasspath: Boolean,
  ): CurrentCoordinates {
    val declared = getResolvableDependencies(configuration).associateBy { it.key }
    // An empty configuration is still resolved below, so that a listener contributing to it has
    // run by the time the declared set is read again. That resolution costs nothing. One
    // containing only project or file dependencies is skipped, as resolving it would not, unless it
    // imports a platform: scan first, and keep the cheap exit when the scan finds nothing. This
    // path resolved nothing at all before, so the scan is its first resolution rather than a second
    // one.
    if (declared.isEmpty() && configuration.allDependencies.isNotEmpty()) {
      val platformSources = if (checkConstraints) getPlatformSources(configuration, declared.keys) else emptyList()
      if (platformSources.isEmpty()) {
        return CurrentCoordinates(emptyMap(), emptyMap(), emptySet())
      }
      return CurrentCoordinates(
        platformSources.associateBy { it.key },
        emptyMap(),
        platformSources = platformSources,
      )
    }

    // https://github.com/ben-manes/gradle-versions-plugin/issues/231
    val transitive = declared.values.any { it.version == "none" }

    val coordinates = hashMapOf<Coordinate.Key, Coordinate>()
    val copy = configuration.copyRecursive().setTransitive(transitive)

    // https://github.com/ben-manes/gradle-versions-plugin/issues/781
    copy.resolutionStrategy.deactivateDependencyLocking()

    disableAutoTargetJvm(copy)
    val root = copy.incoming.resolutionResult.root
    val platformConstraints = getPlatformConstraints(copy.incoming.resolutionResult, versionedKeys(configuration))

    for (dependency in root.dependencies) {
      // Constraints are accumulated separately below via allDependencyConstraints.
      if (dependency.isConstraint) {
        continue
      }
      when (dependency) {
        is ResolvedDependencyResult -> {
          val moduleVersion = dependency.selected.moduleVersion ?: continue
          val coordinate = Coordinate.from(moduleVersion, declared, platformConstraints)
          coordinates[coordinate.key] = coordinate
        }
        is UnresolvedDependencyResult -> {
          (dependency.attempted as? ModuleComponentSelector)?.let { selector ->
            val key = Coordinate.Key(selector.group, selector.module)
            declared[key]?.let { coordinates.put(key, it) }
          }
        }
      }
    }

    if (supportsConstraints(copy)) {
      for (constraint in copy.allDependencyConstraints) {
        val coordinate = Coordinate.from(constraint)
        // Only add a constraint to the report if there is no dependency matching it, this means it
        // is targeting a transitive dependency or is part of a platform.
        if (!coordinates.containsKey(coordinate.key)) {
          declared[coordinate.key]?.let { coordinates.put(coordinate.key, it) }
        }
      }
    }

    // A resolution listener contributes dependencies when a configuration is first resolved, which
    // is the resolution above, so they are missing from the declared set read before it. Read it
    // again to pick up their declared version; otherwise only the query below reaches them and
    // their latest version is reported as the declared one, hiding the update.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/992
    val contributed =
      getResolvableDependencies(configuration)
        .filterNot { declared.containsKey(it.key) }
    for (coordinate in contributed) {
      coordinates.putIfAbsent(coordinate.key, coordinate)
    }

    // A substitution rule can replace a declared module with one of a different group or name, so
    // the resolved coordinates are in a different keyspace than the declared ones.
    // https://github.com/ben-manes/gradle-versions-plugin/issues/990
    val substitutions = getSubstitutions(copy, declared)

    // Ignore undeclared (hidden) dependencies that appear when resolving a configuration
    coordinates.keys.retainAll(declared.keys + contributed.map { it.key } + substitutions.values)

    val platformSources = if (checkConstraints) getPlatformSources(configuration, declared.keys) else emptyList()
    val platformSourceKeys = hashSetOf<Coordinate.Key>()
    for (source in platformSources) {
      if (coordinates.putIfAbsent(source.key, source) == null) {
        platformSourceKeys.add(source.key)
      }
    }

    // A key only reached via a lazy action (withDependencies/defaultDependencies, or a resolution
    // listener) is missing from the snapshot taken before any configuration was first resolved, and
    // is not a substitution target, which traces to a real declaration.
    val contributedKeys =
      coordinates.keys - declaredBeforeActions - substitutions.values.toSet() - platformSourceKeys

    // A plugin that adds its private classpath eagerly, at apply time, is indistinguishable from a
    // declaration by the time the snapshot above is taken, so what it leaves behind is reported
    // instead: a dependency declared directly on a resolvable configuration. A build declares
    // against one it cannot resolve, and a resolvable classpath inherits its dependencies rather
    // than having them declared on it.
    val declaringKeys =
      if (nameDeclaringConfiguration) {
        coordinates.keys intersect configuration.externalKeys().toSet()
      } else {
        emptySet()
      }

    // A plugin marker reaches a build only here, and only as a single declaration with nothing to
    // resolve against it, which is what lets its dynamic required version be read as a bound. Only
    // what the classpath declares qualifies: a platform source is discovered by walking a
    // platform's own graph rather than declared here, and a substitution target is reached through
    // a rule rather than written down, so both keep the floor semantics of an ordinary
    // declaration.
    if (scriptClasspath) {
      for (key in declared.keys + contributed.map { it.key }) {
        coordinates[key]?.onScriptClasspath = true
      }
    }

    return CurrentCoordinates(
      coordinates,
      substitutions,
      contributedKeys,
      configurationsOf(configuration, contributedKeys + declaringKeys),
      platformSources,
    )
  }

  /**
   * Returns the external platforms the build consumes through its own platform projects, such as a
   * BOM that an included build's platform imports and this build reaches by project substitution.
   *
   * Resolved from a dedicated copy containing only the configuration's platform-category
   * declarations, always transitive, so a platform reached only through a project dependency (which
   * has no version for the main copy's own #231 flag to key off) is still found, without
   * perturbing the main copy's resolution. That copy is a second resolution of every configuration
   * declaring a platform, so a build's own beforeResolve hook runs once more than it did.
   */
  private fun getPlatformSources(
    configuration: Configuration,
    declaredKeys: Set<Coordinate.Key>,
  ): List<Coordinate> {
    val platformDependencies =
      configuration.allDependencies.filterIsInstance<ModuleDependency>().filter { isPlatform(it) }
    if (platformDependencies.isEmpty() || platformDependencies.toSet() in failedPlatformScans) {
      return emptyList()
    }
    return try {
      getPlatformSources(resolvePlatformRoot(configuration, platformDependencies), declaredKeys)
    } catch (e: Exception) {
      // The scan is an extra resolution the report can do without: it is always transitive and
      // applies the build's own resolution strategy, so a build with failOnVersionConflict() can
      // throw here even though the main, non-transitive copy resolved fine. Losing one
      // attribution beats losing every row of the configuration.
      failedPlatformScans.add(platformDependencies.toSet())
      project.logger.info("Failed to resolve the platforms declared by ${configuration.name}", e)
      emptyList()
    }
  }

  /** Returns the root of resolving the configuration's platform declarations, always transitive. */
  private fun resolvePlatformRoot(
    configuration: Configuration,
    platformDependencies: List<ModuleDependency>,
  ): ResolvedComponentResult {
    val copy = configuration.copyRecursive().setTransitive(true)
    // https://github.com/ben-manes/gradle-versions-plugin/issues/781
    copy.resolutionStrategy.deactivateDependencyLocking()
    disableAutoTargetJvm(copy)
    copy.dependencies.clear()
    // Copied rather than shared, as copyRecursive does for the set cleared above: a withDependencies
    // action copied onto this one would otherwise mutate the build's own declarations.
    copy.dependencies.addAll(platformDependencies.map { it.copy() })
    return copy.incoming.resolutionResult.root
  }

  /**
   * Returns the external platforms found by walking the given root, such as a BOM that an included
   * build's platform imports and this build reaches by project substitution.
   *
   * Such a platform's constraints bound this build's versionless modules while the substituted
   * project leaves the coordinate to edit out of the report, so each one is reported as an entry of
   * its own. Only a chain of platform variants that stays inside project components until the
   * imported module qualifies: a platform imported by an external platform is that platform's
   * version choice rather than the build's, and one brought in by a library is a resolution input,
   * so the walk stops at the first external component either way. A platform the build declares
   * itself already has a report entry and is skipped. The constraint written on the platform
   * project's declaration is kept, so it bounds an imported row like any declared one. Each
   * qualifying edge's dequeued source project, when it is one, is recorded as the coordinate's
   * importer, by build tree path. Every importer of a coordinate is recorded, while the bound comes
   * from the first qualifying edge, so a row printed with several projects shows the bound of one
   * of them.
   */
  private fun getPlatformSources(
    root: ResolvedComponentResult,
    declaredKeys: Set<Coordinate.Key>,
  ): List<Coordinate> {
    val sources = linkedMapOf<Coordinate.Key, Coordinate>()
    val importers = hashMapOf<Coordinate.Key, MutableSet<String>>()
    val seen = hashSetOf(root.id)
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
      val node = pending.removeFirst()
      val importer = (node.id.takeIf { it != root.id } as? ProjectComponentIdentifier)?.buildTreePath
      for (dependency in node.dependencies) {
        if (dependency !is ResolvedDependencyResult || dependency.isConstraint) {
          continue
        }
        val selected = dependency.selected
        // The edge's own variant, rather than the component's set, since a module published with
        // only a pom exposes both a library and a platform variant and a build can consume each.
        // Marking a component seen only once past this gate keeps an edge that resolved a library
        // variant from barring the platform edge that reaches the same project later.
        if (!isPlatform(dependency.resolvedVariant)) {
          continue
        }
        if (selected.id is ProjectComponentIdentifier) {
          if (seen.add(selected.id)) {
            pending.add(selected)
          }
        } else {
          val moduleVersion = selected.moduleVersion ?: continue
          val requested = dependency.requested as? ModuleComponentSelector
          val key = Coordinate.Key(moduleVersion.group, moduleVersion.name)
          if (key in declaredKeys) {
            continue
          }
          sources.putIfAbsent(
            key,
            Coordinate(
              moduleVersion.group,
              moduleVersion.name,
              moduleVersion.version,
              userReason = null,
              versionConstraint = requested?.versionConstraint,
            ),
          )
          if (importer != null) {
            importers.getOrPut(key) { sortedSetOf() }.add(importer)
          }
        }
      }
    }
    // Rebuilt only where an importer was recorded, since the constraint kept by a rebuild is
    // copied again on the way through and an unattributed coordinate is already what it needs to be.
    return sources.map { (key, coordinate) ->
      val importedBy = importers[key] ?: return@map coordinate
      Coordinate(
        coordinate.groupId,
        coordinate.artifactId,
        coordinate.version,
        coordinate.userReason,
        coordinate.versionConstraint,
        coordinate.platformVersionConstraints,
        importedBy.toList(),
      )
    }
  }

  /**
   * Returns the version constraints the consumed platforms set for each module declared without a
   * version of its own, keyed by that module and paired with the platform each came from.
   *
   * Only a constraint edge whose source resolved as a platform qualifies. One from the resolution
   * root is the consumer's own `constraints {}` block, which is editable and thus governed by
   * [Coordinate.versionConstraint] instead, and one an ordinary library ships in its module
   * metadata is a resolution input rather than a platform's version choice. A module the build
   * itself versions is also excluded, since the build controls that number and a plain declaration
   * keeps its floor semantics.
   * https://github.com/ben-manes/gradle-versions-plugin/issues/402
   */
  private fun getPlatformConstraints(
    result: ResolutionResult,
    versionedKeys: Set<Coordinate.Key>,
  ): Map<Coordinate.Key, List<Coordinate.PlatformConstraint>> {
    val constraints = hashMapOf<Coordinate.Key, MutableList<Coordinate.PlatformConstraint>>()
    for (dependency in result.allDependencies) {
      if (dependency !is ResolvedDependencyResult || !dependency.isConstraint) {
        continue
      }
      if (dependency.from.id == result.root.id) {
        continue
      }
      if (dependency.from.variants.none { isPlatform(it) }) {
        continue
      }
      val requested = dependency.requested as? ModuleComponentSelector ?: continue
      val versionConstraint = requested.versionConstraint
      // A strictly-only constraint arrives with an empty required version; Gradle's own publisher
      // writes requires beside strictly, but other tooling's module metadata need not.
      if (versionConstraint.requiredVersion.isEmpty() &&
        versionConstraint.strictVersion.isEmpty() &&
        versionConstraint.rejectedVersions.isEmpty()
      ) {
        continue
      }
      val key = Coordinate.Key(requested.group, requested.module)
      if (key in versionedKeys) {
        continue
      }
      val source =
        when (val from = dependency.from.id) {
          is ProjectComponentIdentifier -> from.buildTreePath
          // Printed without its version: the one here is the resolved version rather than any
          // declared in the build, and the platform's own row in the same report already shows it.
          is ModuleComponentIdentifier -> "${from.group}:${from.module}"
          else -> continue
        }
      constraints
        .getOrPut(key) { mutableListOf() }
        .add(Coordinate.PlatformConstraint(source, versionConstraint))
    }
    return constraints
  }

  /**
   * Returns the modules that any declaration in the configuration's hierarchy declares a version
   * for.
   *
   * The declared coordinates keep only the last declaration of a module, so a versionless one in a
   * configuration can mask a versioned one in another it extends. Reading every declaration keeps a
   * platform from bounding a version the build declares somewhere it can edit.
   */
  private fun versionedKeys(configuration: Configuration): Set<Coordinate.Key> =
    getResolvableDependencies(configuration)
      .filterNot { it.version == "none" }
      .mapTo(hashSetOf()) { it.key }

  /** Returns the modules that a resolution rule substituted for a declared one, by declared key. */
  private fun getSubstitutions(
    resolved: Configuration,
    declared: Map<Coordinate.Key, Coordinate>,
  ): Map<Coordinate.Key, Coordinate.Key> {
    val substitutions = hashMapOf<Coordinate.Key, Coordinate.Key>()
    for (dependency in resolved.incoming.resolutionResult.root.dependencies) {
      if (dependency !is ResolvedDependencyResult) {
        continue
      }
      val requested = dependency.requested as? ModuleComponentSelector ?: continue
      val selected = dependency.selected.moduleVersion ?: continue
      val requestedKey = Coordinate.Key(requested.group, requested.module)
      val selectedKey = Coordinate.Key(selected.group, selected.name)
      if (requestedKey != selectedKey && declared.containsKey(requestedKey)) {
        substitutions[requestedKey] = selectedKey
      }
    }
    return substitutions
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
      val resolutionResult =
        project.dependencies
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
            var url = interpolate(getUrlFromPom(file), id)
            if (!url.isNullOrEmpty()) {
              project.logger.info("Found url for $id: $url")
              return url.trim()
            } else {
              val parent = getParentFromPom(file)
              if (parent != null &&
                "${parent.group.orEmpty()}:${parent.name}" != "org.sonatype.oss:oss-parent"
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
    return checkConstraints && !configuration.allDependencyConstraints.isNullOrEmpty()
  }

  private fun getResolvableDependencies(configuration: Configuration): List<Coordinate> {
    @Suppress("SimplifiableCall")
    val coordinates =
      configuration.allDependencies
        .filter { dependency -> dependency is ExternalDependency }
        .mapTo(mutableListOf()) { dependency ->
          Coordinate.from(dependency)
        }

    if (supportsConstraints(configuration)) {
      configuration.allDependencyConstraints.forEach { dependencyConstraint ->
        coordinates.add(Coordinate.from(dependencyConstraint))
      }
    }
    return coordinates
  }

  /**
   * The declared dependencies as resolved, the modules substituted for any of them, and the keys
   * that only a lazy action contributed rather than the build declaring them.
   */
  private class CurrentCoordinates(
    val coordinates: Map<Coordinate.Key, Coordinate>,
    val substitutions: Map<Coordinate.Key, Coordinate.Key>,
    val contributedKeys: Set<Coordinate.Key> = emptySet(),
    val contributedConfigurations: Map<Coordinate.Key, List<String>> = emptyMap(),
    val platformSources: List<Coordinate> = emptyList(),
  )

  companion object {
    private val PROJECT_PROPERTY = Regex("""\$\{project\.(groupId|artifactId|version)}""")
    private val ABSOLUTE_URL = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""")
    private val DESUGARED_CATEGORY = Attribute.of(Category.CATEGORY_ATTRIBUTE.name, String::class.java)

    /** Whether the category is that of a regular or enforced platform. */
    private fun isPlatformCategory(category: String?): Boolean =
      (category == Category.REGULAR_PLATFORM) || (category == Category.ENFORCED_PLATFORM)

    /**
     * Whether the variant is a platform's. A local project's variant has the typed [Category]
     * attribute while a published module's is desugared to a string, so both forms are read.
     */
    private fun isPlatform(variant: ResolvedVariantResult): Boolean {
      val category =
        variant.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
          ?: variant.attributes.getAttribute(DESUGARED_CATEGORY)
      return isPlatformCategory(category)
    }

    /**
     * Whether the dependency declares a platform. A declaration created by `platform(...)` or
     * `enforcedPlatform(...)` always has the typed [Category] attribute; the desugared string
     * form appears only on published module metadata, which the resolved-variant overload above
     * reads instead.
     */
    private fun isPlatform(dependency: ModuleDependency): Boolean =
      isPlatformCategory(dependency.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name)

    private fun getUrlFromPom(file: File): String? {
      val pom = XmlSlurper(false, false).parse(file)
      return (pom.getProperty("url") as NodeChildren?)?.text()
        ?: ((pom.getProperty("scm") as NodeChildren?)?.getProperty("url") as NodeChildren?)?.text()
    }

    /** Returns the url with the project properties resolved, or null if it is not usable as one. */
    private fun interpolate(
      url: String?,
      id: ModuleVersionIdentifier,
    ): String? {
      if (url == null || !url.contains("\${")) {
        return url
      }
      val resolved =
        PROJECT_PROPERTY.replace(url) { match ->
          when (match.groupValues[1]) {
            "groupId" -> id.group
            "artifactId" -> id.name
            else -> id.version
          }
        }
      return resolved.takeIf { !it.contains("\${") && ABSOLUTE_URL.containsMatchIn(it) }
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

/**
 * Returns the keys that appear only in the named configurations, taken across the given one and the
 * configurations it extends, so that the dependencies of a configuration a plugin alone filled are
 * known.
 *
 * A key that also appears in another configuration in the hierarchy is left out, as the build
 * declaring a module that a plugin happens to contribute elsewhere is a declaration of it and not a
 * plugin's.
 */
internal fun keysOf(
  configuration: Configuration,
  names: Set<String>,
): Set<Coordinate.Key> {
  val filled = hashSetOf<Coordinate.Key>()
  val declared = hashSetOf<Coordinate.Key>()
  val pending = ArrayDeque(listOf(configuration))
  val seen = hashSetOf<String>()
  while (pending.isNotEmpty()) {
    val next = pending.removeFirst()
    if (!seen.add(next.name)) {
      continue
    }
    (if (next.name in names) filled else declared).addAll(next.externalKeys())
    pending.addAll(next.extendsFrom)
  }
  return filled - declared
}

/** Returns the keys of the external dependencies declared on the configuration itself. */
internal fun Configuration.externalKeys(): List<Coordinate.Key> =
  dependencies
    .filterIsInstance<ExternalDependency>()
    .map { Coordinate.from(it as Dependency).key }

/**
 * Returns the configurations each of the given keys appears in, taken across the given
 * configuration and the ones it extends, so that a contributed dependency is reported against where
 * it was declared rather than against the resolvable configuration it was reached through.
 */
internal fun configurationsOf(
  configuration: Configuration,
  keys: Set<Coordinate.Key>,
): Map<Coordinate.Key, List<String>> {
  if (keys.isEmpty()) {
    return emptyMap()
  }
  val names = hashMapOf<Coordinate.Key, MutableSet<String>>()
  val pending = ArrayDeque(listOf(configuration))
  val seen = hashSetOf<String>()
  while (pending.isNotEmpty()) {
    val next = pending.removeFirst()
    if (!seen.add(next.name)) {
      continue
    }
    val held =
      next.externalKeys() +
        // A constraint contributes a key of its own, which the dependencies alone do not name.
        next.dependencyConstraints.map { Coordinate.from(it).key }
    for (key in held) {
      if (key in keys) {
        names.getOrPut(key) { sortedSetOf() }.add(next.name)
      }
    }
    pending.addAll(next.extendsFrom)
  }
  return names.mapValues { (_, held) -> held.toList() }
}
