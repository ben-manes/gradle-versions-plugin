package com.github.benmanes.gradle.versions.reporter.result

class VersionAvailable
  constructor(
    val release: String?,
    val milestone: String?,
    val integration: String?,
    /**
     * The newest candidate left out by the pre-release check, null when there is none or when
     * `rejectPreReleases` is set on the report. Newer than the version in the three fields above,
     * which is the newest the resolution accepted.
     */
    val preRelease: String?,
    /**
     * The newest version sharing the major and minor parts of the version in use, null where none
     * is newer. Never newer than the version in the three revision fields.
     */
    val patch: String?,
    /** The newest version sharing the major part of the version in use, null where none is newer. */
    val minor: String?,
  ) {
    /**
     * Keeps the constructors a release shipped callable, including the one a Kotlin caller of named
     * or default arguments is compiled against, which trailing defaulted parameters would replace.
     */
    @JvmOverloads
    constructor(
      release: String? = null,
      milestone: String? = null,
      integration: String? = null,
      preRelease: String? = null,
    ) : this(release, milestone, integration, preRelease, null, null)

    /**
     * Returns the version available at [revision], and the release-level one for a revision outside
     * the three levels, which is where the report files the version it found for such a revision.
     * Reading back an empty string there left every reporter printing a row with no version at all.
     */
    operator fun get(revision: String): String? {
      return when (revision) {
        "milestone" -> milestone
        "integration" -> integration
        else -> release
      }
    }
  }
