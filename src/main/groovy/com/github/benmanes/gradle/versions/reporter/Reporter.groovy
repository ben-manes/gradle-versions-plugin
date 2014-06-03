package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result;

/**
 * An interface for reporters.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
public interface Reporter {
  def write(printStream, Result result);

  def getFileName();
}