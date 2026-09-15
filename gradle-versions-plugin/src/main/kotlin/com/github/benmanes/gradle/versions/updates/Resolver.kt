package com.github.benmanes.gradle.versions.updates

import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentFilter
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent
import com.github.benmanes.gradle.versions.updates.resolutionstrategy.ResolutionStrategyWithCurrent
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.NodeChildren
import org.codehaus.groovy.runtime.DefaultGroovyMethods.asBoolean
import org.codehaus.groovy.runtime.DefaultGroovyMethods.getMetaClass
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
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
import org.gradle.api.logging.Logger
import org.gradle.api.specs.Spec
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The modules a version tier queries: the declared version whose leading parts a candidate has to
 * share, and the prefix selector that asks for those candidates, by module.
 */
private class TierQuery(
  val tier: VersionTier,
  val declaredVersions: Map<Coordinate.Key, String>,
  val selectors: Map<Coordinate.Key, String>,
)

/**
 * Resolves the configuration to determine the version status of its dependencies.
 */
class Resolver internal constructor(
  private val project: Project,
  private val resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
  private val checkConstraints: Boolean,
  private val rejectOutOfBounds: Boolean,
  /** The convention added to the pre-release check in the build, null when none is configured. */
  preReleaseVersionIf: Spec<String>?,
  /** The candidates exempted from both built-in checks in the build, null when none is configured. */
  exemptFromBuiltInChecksIf: ComponentFilter?,
  /**
   * The container that holds the settings script's classpath, which belongs to no project, so that
   * the recording walk detaches from where the configuration came from. Null wherever it could not
   * hold what is being resolved, which is every pass but the script classpaths of the root.
   */
  private val settingsConfigurations: ConfigurationContainer?,
  /** Called when a rule reads the deprecated bound, so the warning is printed once per project. */
  private val onDeprecatedBoundRead: () -> Unit,
) {
  /**
   * Retained so the arity released before the bound and pre-release filters were added still links.
   * The bound filter is on, and the pre-release check is applied to every resolution.
   */
  constructor(
    project: Project,
    resolutionStrategy: Action<in ResolutionStrategyWithCurrent>?,
    checkConstraints: Boolean,
  ) : this(
    project,
    resolutionStrategy,
    checkConstraints,
    rejectOutOfBounds = true,
    preReleaseVersionIf = null,
    exemptFromBuiltInChecksIf = null,
    settingsConfigurations = null,
    onDeprecatedBoundRead = deprecatedBoundWarning(project.logger),
  )

  /**
   * Whether a version is a pre-release, by the built-in markers or by the convention added in the
   * build. Handed to every selection wrapper, so the built-in filter and a rule calling
   * `isPreRelease` read one definition.
   */
  private val isPreRelease: (String) -> Boolean = VersionStability.withConvention(preReleaseVersionIf)

  /** Whether a candidate is exempt from the built-in checks; nothing is exempt unless configured. */
  private val isExempt: (ComponentSelectionWithCurrent) -> Boolean =
    if (exemptFromBuiltInChecksIf == null) {
      { false }
    } else {
      { current -> exemptFromBuiltInChecksIf.reject(current) }
    }

  // https://github.com/ben-manes/gradle-versions-plugin/issues/1095
  // Gradle prints a line at the default log level for every configuration that opts out of
  // dependency verification, so a build that verifies nothing would be told hundreds of times
  // about an opt out it has no use for. Verification runs from the metadata file alone, so where
  // that file is absent no lookup can fail one.
  private val verifiesDependencies: Boolean =
    project.rootDir.resolve("gradle/verification-metadata.xml").exists()

  private var projectUrls = ConcurrentHashMap<ModuleVersionIdentifier, ProjectUrl>()

  // One comparator for the resolver, where the configurations of a project resolve concurrently:
  // the parser behind it caches every version string it reads in a ConcurrentHashMap, so it is
  // shared rather than rebuilt, as the declared bound's own parser is.
  private val versionComparator = VersionMapping.versionComparator()

  // Every candidate a dynamic query's component-selection walk reached, as `group:name:version`,
  // deduped in the order they arrived. Selections run concurrently, so both the set and each drain
  // of it are synchronized.
  internal val candidates: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

  // The platform declarations whose scan threw, so a configuration inheriting the same ones does
  // not repeat a resolution already known to fail. Only a failure is shared: what a scan finds
  // depends on the resolving configuration's own attributes, constraints and resolution strategy,
  // while skipping one costs an attribution at most.
  private val failedPlatformScans = hashSetOf<Set<ModuleDependency>>()

  init {
    logRepositories()
  }

  /** Exempts a lookup from dependency verification, in a build that verifies at all. */
  private fun exemptFromDependencyVerification(copy: Configuration) {
    if (verifiesDependencies) {
      copy.resolutionStrategy.disableDependencyVerification()
    }
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
    // Filled by the pre-release filter as the first-accept walk reaches each rejected candidate,
    // and read once the walk below has run. Local to the resolution rather than held on the
    // resolver, which resolves the configurations of a project concurrently.
    val preReleases = ConcurrentHashMap<Coordinate.Key, String>()
    val latestConfiguration = createLatestConfiguration(configuration, revision, current, preReleases)
    val root = latestConfiguration.incoming.resolutionResult.root
    // The recording pass enriches the report rather than producing it, so a failure in it costs
    // the candidate lists, and with them the patch and minor versions, which are queried only where
    // those lists hold a later version. Letting it throw would discard every status the
    // first-accept walk above already resolved and report the whole configuration as skipped.
    runCatching { recordAllCandidates(configuration, current) }
      .onFailure { e ->
        project.logger.info("Skipping the recorded candidates of ${configuration.name}", e)
      }
    val statuses = getStatus(current, root, preReleases)
    resolveVersionTiers(configuration, revision, current, statuses)
    return statuses
  }

  /**
   * Sets the latest patch and the latest minor version on each status that has a later version.
   * Where the latest version shares the parts of a tier, it is the latest of that tier as well.
   * Otherwise the tier is resolved as the latest version is, through a copy of the configuration
   * that queries a prefix selector such as `1.0.+`, so the build's own rules, the metadata and
   * variant selection all apply to it, and the build's own resolution strategy is applied to each
   * tier copy, so a rule with a side effect runs once more per queried tier. A tier is queried only
   * where the recorded candidates hold a later version inside it that the prefix reaches, as a query
   * that matches nothing costs a lookup and finds nothing.
   * https://github.com/ben-manes/gradle-versions-plugin/issues/69
   */
  private fun resolveVersionTiers(
    configuration: Configuration,
    revision: String,
    current: CurrentCoordinates,
    statuses: Set<DependencyStatus>,
  ) {
    // Grouped rather than keyed, as a configuration can report one module twice: a Kotlin module the
    // query copy inherits at its declared version beside its query.
    val upgrades =
      statuses
        .filter { it.unresolved == null && versionComparator.compare(it.latestVersion, it.coordinate.version) > 0 }
        .groupBy { it.coordinate.key }
    if (upgrades.isEmpty()) {
      return
    }
    // Partitioned by module once, since each module of each tier is looked up in the listing.
    val listedVersions =
      synchronized(candidates) { candidates.toList() }
        .groupBy({ it.substringBeforeLast(':') }, { it.substringAfterLast(':') })
    for (tier in listOf(VersionTier.PATCH, VersionTier.MINOR)) {
      val declaredVersions = mutableMapOf<Coordinate.Key, String>()
      val selectors = mutableMapOf<Coordinate.Key, String>()
      for ((key, statusesOfKey) in upgrades) {
        val status = statusesOfKey.first()
        val declared = status.coordinate.version
        // Null for a declared selector such as `1.+`, which reaches here as text where nothing
        // resolved it, and which already selects every version in its tier.
        val selector = VersionTiers.selector(declared, tier.parts) ?: continue
        if (VersionTiers.shares(status.latestVersion, declared, tier.parts)) {
          statusesOfKey.forEach { it.setTierVersion(tier, it.latestVersion) }
          continue
        }
        val prefix = selector.dropLast(1)
        val later =
          listedVersions["${key.groupId}:${key.artifactId}"].orEmpty().any { version ->
            version.startsWith(prefix) &&
              VersionTiers.shares(version, declared, tier.parts) &&
              versionComparator.compare(version, declared) > 0
          }
        if (later) {
          declaredVersions[key] = declared
          selectors[key] = selector
        }
      }
      if (declaredVersions.isEmpty()) {
        continue
      }
      val query = TierQuery(tier, declaredVersions, selectors)
      val resolved = createLatestConfiguration(configuration, revision, current, ConcurrentHashMap(), query)
      for (dependency in resolved.incoming.resolutionResult.root.dependencies) {
        // The copy's constraints are cleared, so none is expected here; skipped as getStatus does.
        if (dependency.isConstraint) {
          continue
        }
        val moduleVersion = (dependency as? ResolvedDependencyResult)?.selected?.moduleVersion ?: continue
        val version = moduleVersion.version
        for (status in upgrades[Coordinate.from(moduleVersion).key].orEmpty()) {
          // A rule that forces or substitutes a version can move the query outside its prefix.
          if (VersionTiers.shares(version, status.coordinate.version, tier.parts) &&
            versionComparator.compare(version, status.coordinate.version) > 0 &&
            versionComparator.compare(version, status.latestVersion) <= 0
          ) {
            status.setTierVersion(tier, version)
          }
        }
      }
    }
  }

  /** Returns the version status of the configuration's dependencies. */
  private fun getStatus(
    current: CurrentCoordinates,
    root: ResolvedComponentResult,
    preReleases: Map<Coordinate.Key, String>,
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
            DependencyStatus(
              coord,
              resolvedCoordinate.version,
              projectUrl,
              contributed,
              configurations,
              preReleases[coord.key],
            ),
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
    preReleases: MutableMap<Coordinate.Key, String>,
    /** The version tier to query, null to query every module with `+`. */
    tier: TierQuery? = null,
  ): Configuration {
    val latest = queryDependencies(configuration, current, tier)

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

    // https://github.com/ben-manes/gradle-versions-plugin/issues/1095
    // The candidate versions cannot be in the build's verification metadata, as they are the newer
    // versions being searched for.
    exemptFromDependencyVerification(copy)

    tier?.let { addTierFilter(copy, it) }
    addDeclaredBoundFilter(copy, current.coordinates)
    addRevisionFilter(copy, revision, current.coordinates)
    addAttributes(copy, configuration)
    addCustomResolutionStrategy(copy, current.coordinates)
    addPreReleaseFilter(copy, current.coordinates, preReleases)

    disableAutoTargetJvm(copy)
    return copy
  }

  /**
   * Returns the `+` query dependencies used to resolve the configuration's latest versions, or the
   * prefix queries of the modules in [tier] alone where it is given.
   */
  private fun queryDependencies(
    configuration: Configuration,
    current: CurrentCoordinates,
    tier: TierQuery? = null,
  ): MutableList<Dependency> {
    val tierSelectors = tier?.selectors
    val latest =
      configuration.allDependencies
        .filterIsInstance<ExternalDependency>()
        .mapNotNullTo(mutableListOf()) { dependency ->
          createQueryDependency(dependency as ModuleDependency, current.substitutions, tierSelectors)
        }

    // Common use case for dependency constraints is a java-platform BOM project or to control
    // version of transitive dependency.
    if (supportsConstraints(configuration)) {
      for (dependency in configuration.allDependencyConstraints) {
        // A constraint without a version is queried only where a current version was resolved for it.
        if (dependency !is DefaultProjectDependencyConstraint &&
          (dependency.version != null || Coordinate.keyFrom(dependency) in current.coordinates)
        ) {
          createQueryDependency(dependency, tierSelectors)?.let { latest.add(it) }
        }
      }
    }

    for (source in current.platformSources) {
      createPlatformQueryDependency(source, tierSelectors)?.let { latest.add(it) }
    }
    return latest
  }

  /**
   * Resolves a policy-free copy of the configuration that queries the same dynamic versions as
   * [createLatestConfiguration] but rejects every candidate a component-selection walk reaches, so
   * [recordCandidates] observes the complete listing rather than the prefix a first-accept walk
   * reaches. Built from a detached configuration rather than [Configuration.copyRecursive], which
   * copies the source configuration's `resolutionStrategy` as well: a build-script `force`,
   * `eachDependency`, or `componentSelection` rule would then alter the facts before the rejected
   * candidates ever reach the recording rule. Neither the revision filter nor the build's
   * `resolutionStrategy` is applied here: facts are policy-free by definition, and a
   * metadata-reading user predicate must never turn this walk into the far more expensive
   * per-candidate fetch those add. The
   * resolved result is discarded; a rejected candidate surfaces as an `UnresolvedDependencyResult`
   * inside it, never as a thrown exception, the same tolerance [getStatus] already relies on for the
   * first-accept walk.
   *
   * Runs beside [createLatestConfiguration] rather than replacing it. The verdict that walk bakes
   * is itself a recorded fact—the newest candidate this build's own policy accepted and resolved—and
   * the only place the revision filter, the build's configuration-level selection rules and its
   * `force`/`eachDependency` effects are applied, none of which the aggregating task can replay over
   * a listing. It is also where `projectUrl`, the classification of a genuine resolution failure,
   * and the proof that a usable variant of the reported version exists come from.
   */
  private fun recordAllCandidates(
    configuration: Configuration,
    current: CurrentCoordinates,
  ) {
    val copy = containerOf(configuration).detachedConfiguration().setTransitive(false)
    if (asBoolean(
        getMetaClass(copy.resolutionStrategy)
          .hasProperty(copy.resolutionStrategy, "failOnDynamicVersions"),
      )
    ) {
      getMetaClass(copy.resolutionStrategy)
        .setProperty(copy.resolutionStrategy, "failOnDynamicVersions", false)
    }
    copy.dependencies.addAll(queryDependencies(configuration, current))
    copy.resolutionStrategy.deactivateDependencyLocking()

    // https://github.com/ben-manes/gradle-versions-plugin/issues/1095
    // As for the copy that resolves the latest versions: the candidates walked here are the newer
    // versions being searched for, so none of them can be in the build's verification metadata.
    exemptFromDependencyVerification(copy)
    recordCandidates(copy)
    copy.incoming.resolutionResult.root
  }

  /**
   * Returns the container that holds the configuration, which the recording walk detaches from as a
   * buildscript's classpath resolves against the buildscript's repositories rather than the
   * project's. Matched by identity rather than by [ConfigurationContainer.contains], which matches
   * by name: a settings script's classpath and a project buildscript's are both named `classpath`,
   * so a name match sends the settings one to the project's own buildscript repositories, which a
   * build that declares its plugins through `pluginManagement` leaves empty.
   */
  private fun containerOf(configuration: Configuration): ConfigurationContainer {
    val settings = settingsConfigurations
    if (settings != null && settings.any { it === configuration }) {
      return settings
    }
    return if (project.buildscript.configurations.any { it === configuration }) {
      project.buildscript.configurations
    } else {
      project.configurations
    }
  }

  /** Returns a variant of the provided dependency used for querying the latest version.  */
  private fun createQueryDependency(
    dependency: ModuleDependency,
    substitutions: Map<Coordinate.Key, Coordinate.Key>,
    tierSelectors: Map<Coordinate.Key, String>? = null,
  ): Dependency? {
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
    val key = Coordinate.from(dependency as Dependency).key
    val substitute = substitutions[key]
    val tierVersion = tierSelectors?.let { selectors -> selectors[substitute ?: key] ?: return null }

    // Format the query with an optional classifier and extension
    var query =
      "${substitute?.groupId ?: dependency.group.orEmpty()}:${substitute?.artifactId ?: dependency.name}:${tierVersion ?: version}"
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
  private fun createPlatformQueryDependency(
    coordinate: Coordinate,
    tierSelectors: Map<Coordinate.Key, String>? = null,
  ): Dependency? {
    val version = if (tierSelectors == null) "+" else tierSelectors[coordinate.key] ?: return null
    val dependency =
      project.dependencies.create("${coordinate.groupId}:${coordinate.artifactId}:$version") as ModuleDependency
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
  private fun createQueryDependency(
    dependency: DependencyConstraint,
    tierSelectors: Map<Coordinate.Key, String>? = null,
  ): Dependency? {
    val version = if (tierSelectors == null) "+" else tierSelectors[Coordinate.keyFrom(dependency)] ?: return null
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

  /**
   * Records every candidate a dynamic query reaches and rejects it, so the walk keeps going rather
   * than stopping at the first one Gradle would otherwise accept. Reads only the candidate's
   * version, never its metadata: a metadata read costs an extra request per candidate that the
   * facts do not otherwise need.
   */
  private fun recordCandidates(configuration: Configuration) {
    configuration.resolutionStrategy { strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          val candidate = selection.candidate
          candidates.add("${candidate.group}:${candidate.module}:${candidate.version}")
          selection.reject("Recorded as a fact; rejected so the walk records every candidate")
        }
      }
    }
  }

  /**
   * Adds the filter that leaves out a candidate outside the queried tier, which the prefix selector
   * matches as text. Registered ahead of the plugin's other filters, so the revision filter reads no
   * metadata for such a candidate. A rule declared on the configuration itself is copied in ahead of
   * every filter and runs first, so one that reads a candidate's metadata reads it for these too.
   */
  private fun addTierFilter(
    configuration: Configuration,
    query: TierQuery,
  ) {
    configuration.resolutionStrategy { strategy ->
      strategy.componentSelection { rules ->
        rules.all { selection ->
          val candidate = selection.candidate
          val declared = query.declaredVersions[Coordinate.Key(candidate.group, candidate.module)]
          if (declared != null && !VersionTiers.shares(candidate.version, declared, query.tier.parts)) {
            selection.reject("Outside the queried version tier")
          }
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
        val revisionFilter = { selection: ComponentSelection ->
          // A module published only as a snapshot has no candidate that a milestone or release
          // revision accepts, so the version the build already uses is exempt from the check.
          // https://github.com/ben-manes/gradle-versions-plugin/issues/475
          val candidateCoordinate = Coordinate.from(selection.candidate)
          val isCurrent =
            currentCoordinates[candidateCoordinate.key]?.version == candidateCoordinate.version
          val metadata = selection.metadata
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
        // Every supported Gradle publishes ComponentSelection.getMetadata, which the guard here
        // reflected over the type's whole member list once per candidate to establish.
        rules.all { selectionAction -> revisionFilter(selectionAction) }
      }
    }
  }

  /**
   * Adds the filter that leaves out the upgrades outside the bound declared for a module, which
   * [ComponentSelectionWithCurrent.isUpgradeOutOfDeclaredBounds] identifies, except for a candidate
   * exempted with `exemptFromBuiltInChecksIf`.
   *
   * Registered first, ahead of the revision filter and the rules configured in the build, since a
   * rejected candidate is not passed to the rules that follow: the revision filter reads each
   * candidate's metadata, which resolves it, and a rule can only reject, so an out-of-bound
   * candidate costs nothing further and the report is the same. The version in use is never
   * rejected here, so a rule reading the deprecated bound is still warned.
   */
  private fun addDeclaredBoundFilter(
    configuration: Configuration,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
  ) {
    if (!rejectOutOfBounds) {
      return
    }
    configuration.resolutionStrategy { inner ->
      ResolutionStrategyWithCurrent(inner, currentCoordinates, {}, isPreRelease).componentSelection { rules ->
        rules.all(
          Action<ComponentSelectionWithCurrent> { current ->
            if (current.isOutOfDeclaredBounds() && !isExempt(current)) {
              current.reject("Rejected by rejectOutOfBounds")
            }
          },
        )
      }
    }
  }

  /**
   * Adds the filter that leaves out a pre-release candidate while the current version is a release,
   * as [ComponentSelectionWithCurrent.isPreRelease] reads both, and records the newest candidate it
   * rejects as the row's pre-release step. Registered on the configuration rather than through
   * [DependencyUpdatesTask.rejectVersionIf]: that setter marks the task's parameters as having a
   * resolution strategy, and a project marked that way is resolved with its own strategy instead of
   * its nearest ancestor's, so routing this filter through it would stop every subproject
   * inheriting the root's `rejectVersionIf`.
   *
   * Registered last, after the bound and revision filters and after the build's own rules, which
   * is the opposite of what [addDeclaredBoundFilter] describes and is what makes the recorded
   * version exact: a rejected candidate is not passed to the rules that follow, so a candidate that
   * reaches this filter passed every other one, and the first rejection here is the newest version
   * that fails the pre-release check alone. The cost of the later position is the revision filter's
   * metadata read on the pre-release candidates above the verdict, bounded by how many of those a
   * repository publishes. The build's own rules are now evaluated for those candidates, which the
   * earlier position kept from them.
   *
   * The newest rejection is kept by the comparator rather than the first one reached. A module
   * found in more than one repository is walked newest-first per repository rather than newest-first
   * overall, so the first rejection is the newest of one repository alone.
   *
   * Installed whether or not `rejectPreReleases` is set, since that setting governs whether the
   * recorded step is printed rather than how the configuration resolves.
   */
  private fun addPreReleaseFilter(
    configuration: Configuration,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
    preReleases: MutableMap<Coordinate.Key, String>,
  ) {
    configuration.resolutionStrategy { inner ->
      ResolutionStrategyWithCurrent(inner, currentCoordinates, {}, isPreRelease).componentSelection { rules ->
        rules.all(
          Action<ComponentSelectionWithCurrent> { current ->
            if (current.isPreRelease() && !isExempt(current)) {
              val candidate = current.candidate
              val key = Coordinate.Key(candidate.group, candidate.module)
              preReleases.merge(key, candidate.version) { seen, found ->
                if (versionComparator.compare(seen, found) >= 0) seen else found
              }
              current.reject("Pre-release rejected by rejectPreReleases")
            }
          },
        )
      }
    }
  }

  /** Adds a custom resolution strategy only applicable for the dependency updates task.  */
  private fun addCustomResolutionStrategy(
    configuration: Configuration,
    currentCoordinates: Map<Coordinate.Key, Coordinate>,
  ) {
    configuration.resolutionStrategy { inner ->
      resolutionStrategy?.execute(
        ResolutionStrategyWithCurrent(
          inner,
          currentCoordinates,
          onDeprecatedBoundRead,
          isPreRelease,
        ),
      )
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

    // https://github.com/ben-manes/gradle-versions-plugin/issues/1095
    exemptFromDependencyVerification(copy)

    disableAutoTargetJvm(copy)

    // A constraint declared without a version enters the resolution result only once a dependency
    // reaches its module, so each one is added as a versionless dependency and reported at the
    // version resolved for it, as a versionless dependency is. It is non-transitive, so the modules
    // it depends on stay out of the graph.
    val versionlessConstraintKeys = getVersionlessConstraintKeys(configuration)
    for (key in versionlessConstraintKeys) {
      val query = project.dependencies.create("${key.groupId}:${key.artifactId}") as ModuleDependency
      query.isTransitive = false
      copy.dependencies.add(query)
    }
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
            // A versionless constraint that nothing sets a version for has no version to update.
            if (key !in versionlessConstraintKeys) {
              declared[key]?.let { coordinates.put(key, it) }
            }
          }
        }
      }
    }

    if (supportsConstraints(copy)) {
      for (constraint in copy.allDependencyConstraints) {
        val coordinate = Coordinate.from(constraint)
        // Only add a constraint to the report if there is no dependency matching it, this means it
        // is targeting a transitive dependency or is part of a platform.
        // A versionless one is reported only where its query above resolved.
        if (constraint.version != null && !coordinates.containsKey(coordinate.key)) {
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

    // https://github.com/ben-manes/gradle-versions-plugin/issues/1095
    exemptFromDependencyVerification(copy)
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

  /**
   * Returns the modules constrained without a version, leaving out any also declared as a dependency,
   * which is resolved and reported on its own.
   */
  private fun getVersionlessConstraintKeys(configuration: Configuration): Set<Coordinate.Key> {
    if (!supportsConstraints(configuration)) {
      return emptySet()
    }
    val dependencyKeys =
      configuration.allDependencies.filterIsInstance<ExternalDependency>().mapTo(hashSetOf()) { Coordinate.keyFrom(it) }
    return configuration.allDependencyConstraints
      .filter { it !is DefaultProjectDependencyConstraint && it.version == null }
      .mapTo(hashSetOf()) { Coordinate.keyFrom(it) }
      .minus(dependencyKeys)
  }

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
      // https://github.com/ben-manes/gradle-versions-plugin/issues/1095
      // An ArtifactResolutionQuery cannot be exempted from dependency verification, as there is
      // no resolution strategy on it, and the candidate version is never in the build's metadata.
      // The artifact-only notation is what keeps an included build that substitutes the module
      // from being built to produce its jar. The coordinates are passed as fields rather than
      // interpolated into a string, which an '@' in the version would be read as splitting.
      val pom =
        project.dependencyFactory
          .create(id.group, id.name, id.version)
          .apply {
            artifact {
              it.name = id.name
              it.type = "pom"
              it.extension = "pom"
            }
          }
      val copy = project.configurations.detachedConfiguration(pom).setTransitive(false)
      exemptFromDependencyVerification(copy)

      // Resolved leniently, so that a module published without a pom is not an error. The
      // failures are logged rather than dropped, as a repository that cannot be reached would
      // otherwise read the same as a module that has no pom to find.
      val artifacts = copy.incoming.artifactView { it.isLenient = true }.artifacts
      for (failure in artifacts.failures) {
        project.logger.info("Failed to resolve the pom of $id", failure)
      }

      // empty for gradle plugins, a single pom for normal dependencies
      for (artifact in artifacts) {
        val file = artifact.file
        project.logger.info("Pom file for $id is $file")
        var url = interpolate(getUrlFromPom(file), id)
        if (!url.isNullOrEmpty()) {
          project.logger.info("Found url for $id: $url")
          return url.trim()
        }

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

/**
 * Warns once, however many rules read the deprecated bound across the resolutions or the report
 * passes it is given to, since a rule is evaluated for every candidate of every configuration and
 * script classpath. Given the logger rather than the project so that the report can warn too: the
 * task that writes the report runs without a project on a restored configuration cache entry.
 */
internal fun deprecatedBoundWarning(logger: Logger): () -> Unit {
  val warned = AtomicBoolean()
  return {
    if (warned.compareAndSet(false, true)) {
      logger.warn(
        "satisfiesDeclaredBound is deprecated; drop it from rejectVersionIf, " +
          "since rejectOutOfBounds applies the declared bound instead.",
      )
    }
  }
}
