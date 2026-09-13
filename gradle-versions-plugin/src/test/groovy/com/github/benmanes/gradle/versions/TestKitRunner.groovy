package com.github.benmanes.gradle.versions

import static java.nio.file.StandardOpenOption.CREATE
import static java.nio.file.StandardOpenOption.WRITE

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import org.gradle.testkit.runner.GradleRunner

/**
 * Creates the runners of the functional specs, so that a task forking its test worker onto an older
 * JDK also drives a Gradle release that JDK starts. A TestKit daemon runs on the worker's JVM, and
 * the release running this build is too new for the oldest JVMs the plugin supports.
 *
 * Each worker runs its builds in a TestKit directory of its own, since four or more workers sharing
 * one directory ran the suite slower than a single worker. A worker takes the first directory in the
 * task's pool that no other worker has locked, so the directories are reused from one run to the next.
 * The pool is outside the build directory, so clean neither removes it nor unlinks a lock file that a
 * running worker holds.
 */
final class TestKitRunner {
  private static final String PINNED = System.getProperty('testGradleVersion')

  /**
   * Held for the worker's lifetime. On JDK 11 and later a FileChannel that nothing references is
   * closed by a cleaner, which releases its lock, so the lock is kept here.
   */
  private static FileLock claim
  private static final File TEST_KIT_DIR = claimTestKitDir(System.getProperty('testKitPool'))

  /**
   * Returns a runner on the release the task pinned, or on the release running this build where the
   * task pinned none. A spec that names its own release overrides either.
   */
  static GradleRunner create() {
    def runner = GradleRunner.create()
    if (TEST_KIT_DIR != null) {
      runner = runner.withTestKitDir(TEST_KIT_DIR)
    }
    return PINNED ? runner.withGradleVersion(PINNED) : runner
  }

  /** Locks the first free directory of the pool until the worker exits, or returns null without a pool. */
  private static File claimTestKitDir(String pool) {
    if (!pool) {
      return null
    }
    for (int slot = 0; ; slot++) {
      def dir = new File("$pool-$slot").absoluteFile
      dir.mkdirs()
      def channel = FileChannel.open(new File(dir, 'claim.lock').toPath(), CREATE, WRITE)
      def lock = channel.tryLock()
      if (lock != null) {
        claim = lock
        return dir
      }
      channel.close()
    }
  }
}
