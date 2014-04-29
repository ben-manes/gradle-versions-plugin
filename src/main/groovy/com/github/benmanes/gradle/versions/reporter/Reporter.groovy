package com.github.benmanes.gradle.versions.reporter

/**
 * An interface for reporters.
 *
 * @author Zenedith (zenedith@wp.pl)
 */
public interface Reporter {
  def writeToConsole(printStream);

  def writeToFile(printStream);

  def getFileName();
}