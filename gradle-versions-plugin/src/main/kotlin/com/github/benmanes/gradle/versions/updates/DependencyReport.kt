package com.github.benmanes.gradle.versions.updates

data class DependencyReport(
  val outdated: OutdatedDependencies,
)

data class OutdatedDependencies(
  val dependencies: List<DependencyInfo>,
)

data class DependencyInfo(
  val group: String,
  val name: String,
  val version: String,
  val available: AvailableVersions,
)

data class AvailableVersions(
  val release: String?,
)
