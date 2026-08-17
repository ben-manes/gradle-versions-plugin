package com.github.benmanes.gradle.versions.reporter.result

data class DependencyLatest
  @JvmOverloads
  constructor(
    override val group: String? = null,
    override val name: String? = null,
    override val version: String? = null,
    override val projectUrl: String? = null,
    override val userReason: String? = null,
    val latest: String,
    @AbsentWhenNull override val projects: List<String>? = null,
    @AbsentWhenNull override val contributed: Boolean? = null,
    @AbsentWhenNull override val configurations: List<String>? = null,
    @AbsentWhenNull override val platformProjects: List<String>? = null,
    @AbsentWhenNull override val constrainedBy: List<String>? = null,
  ) : Dependency() {
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
      latest: String = this.latest,
      projects: List<String>? = this.projects,
      contributed: Boolean? = this.contributed,
      configurations: List<String>? = this.configurations,
      platformProjects: List<String>? = this.platformProjects,
    ): DependencyLatest =
      copy(
        group, name, version, projectUrl, userReason, latest, projects, contributed,
        configurations, platformProjects, constrainedBy,
      )
  }
