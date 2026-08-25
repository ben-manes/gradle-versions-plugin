package com.github.benmanes.gradle.versions.reporter.result

data class DependencyOutdated
  @JvmOverloads
  constructor(
    override val group: String? = null,
    override val name: String? = null,
    override val version: String? = null,
    override val projectUrl: String? = null,
    override val userReason: String? = null,
    val available: VersionAvailable,
    @AbsentWhenNull override val projects: List<String>? = null,
    @AbsentWhenNull override val contributed: Boolean? = null,
    @AbsentWhenNull override val configurations: List<String>? = null,
    @AbsentWhenNull override val platformProjects: List<String>? = null,
    @AbsentWhenNull override val constrainedBy: List<String>? = null,
  ) : Dependency() {
    /**
     * The available version is compared after everything the base class compares, so that two
     * entries of one declared version are ordered apart by the version that separates them rather
     * than only by the projects behind them.
     */
    override fun compareTo(other: Dependency): Int {
      val byDependency = super.compareTo(other)
      if (byDependency != 0 || other !is DependencyOutdated) {
        return byDependency
      }
      return compareValuesBy(
        this,
        other,
        { it.available.release },
        { it.available.milestone },
        { it.available.integration },
      )
    }

    /**
     * Keeps the `copy` a release shipped callable. The generated one no longer is, now that the
     * constraining platforms moved it past ten parameters.
     */
    fun copy(
      group: String? = this.group,
      name: String? = this.name,
      version: String? = this.version,
      projectUrl: String? = this.projectUrl,
      userReason: String? = this.userReason,
      available: VersionAvailable = this.available,
      projects: List<String>? = this.projects,
      contributed: Boolean? = this.contributed,
      configurations: List<String>? = this.configurations,
      platformProjects: List<String>? = this.platformProjects,
    ): DependencyOutdated =
      copy(
        group, name, version, projectUrl, userReason, available, projects, contributed,
        configurations, platformProjects, constrainedBy,
      )
  }
