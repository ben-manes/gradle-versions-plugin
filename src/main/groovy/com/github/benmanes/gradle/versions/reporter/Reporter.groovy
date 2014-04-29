package com.github.benmanes.gradle.versions.reporter

/**
 * An interface for reporters.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
public interface Reporter {
  def writeTo(printStream);
}