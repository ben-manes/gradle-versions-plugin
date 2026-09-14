package com.github.benmanes.gradle.versions

import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker
import com.github.benmanes.gradle.versions.updates.gradle.GradleUpdateChecker.ReleaseStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.gradle.util.GradleVersion
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Timeout

final class GradleUpdateCheckerSpec extends Specification {
  private final CountDownLatch stalled = new CountDownLatch(1)
  private final ExecutorService executor = Executors.newCachedThreadPool()
  private HttpServer server

  def 'setup'() {
    server = HttpServer.create(new InetSocketAddress(InetAddress.loopbackAddress, 0), 0)
    server.executor = executor
    server.start()
  }

  def 'cleanup'() {
    stalled.countDown()
    server.stop(0)
    executor.shutdownNow()
  }

  private void serve(String channel, String body) {
    server.createContext("/${channel}") { HttpExchange exchange ->
      def bytes = body.getBytes('UTF-8')
      exchange.sendResponseHeaders(200, bytes.length)
      exchange.responseBody.withStream { it.write(bytes) }
    }
  }

  private void stall(String channel) {
    server.createContext("/${channel}") { HttpExchange exchange -> stalled.await() }
  }

  private GradleUpdateChecker check() {
    return new GradleUpdateChecker(true, "http://${server.address.hostString}:${server.address.port}/")
  }

  def 'a channel with a version is available and a channel without one is unavailable'() {
    given:
    serve('current', '{"version":"9.9"}')
    serve('release-candidate', '{}')
    serve('nightly', '{"version":"9.10-20260913000000+0000"}')

    when:
    def checker = check()

    then:
    with(checker.currentGradleVersion as ReleaseStatus.Available) {
      gradleVersion == GradleVersion.version('9.9')
    }
    checker.releaseCandidateGradleVersion == ReleaseStatus.Unavailable.INSTANCE
    with(checker.nightlyGradleVersion as ReleaseStatus.Available) {
      gradleVersion == GradleVersion.version('9.10-20260913000000+0000')
    }
  }

  @Issue('https://github.com/ben-manes/gradle-versions-plugin/issues/1119')
  @Timeout(60)
  def 'a channel that never responds is a failure once the check times out'() {
    given:
    stall('current')
    serve('release-candidate', '{}')
    serve('nightly', '{}')

    when:
    def checker = check()

    then:
    checker.currentGradleVersion instanceof ReleaseStatus.Failure
    checker.releaseCandidateGradleVersion == ReleaseStatus.Unavailable.INSTANCE
  }
}
