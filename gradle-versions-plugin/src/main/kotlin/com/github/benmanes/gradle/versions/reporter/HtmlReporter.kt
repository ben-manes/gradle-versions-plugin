package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE
import org.gradle.api.Project
import java.io.OutputStream

/**
 * A HTML reporter for the dependency updates results.
 */
class HtmlReporter(
  override val project: Project,
  override val revision: String,
  override val gradleReleaseChannel: String,
) : AbstractReporter(project, revision, gradleReleaseChannel) {
  override fun write(
    printStream: OutputStream,
    result: Result,
  ) {
    printStream.println("<!doctype html>")
    printStream.println("<html lang=\"en\">")
    writeHeader(printStream)
    writeBody(printStream, result)
    printStream.println("</html>")
  }

  private fun writeHeader(printStream: OutputStream) {
    printStream.println(HEADER.trimMargin())
  }

  private fun writeBody(
    printStream: OutputStream,
    result: Result,
  ) {
    printStream.println("<body>")
    if (result.count == 0) {
      printStream.println("<p>No dependencies found.</p>")
    } else {
      writeUpToDate(printStream, result)
      writeExceedLatestFound(printStream, result)
      writeUpgrades(printStream, result)
      writeUndeclared(printStream, result)
      writeUnresolved(printStream, result)
    }
    writeGradleUpdates(printStream, result)
    printStream.println("</body>")
  }

  private fun writeUpToDate(
    printStream: OutputStream,
    result: Result,
  ) {
    val versions = result.current.dependencies
    if (versions.isNotEmpty()) {
      printStream.println("<h2>Current dependencies</h2>")
      printStream
        .println("<p>The following dependencies are using the latest $revision version:<p>")
      printStream.println("<table class=\"currentInfo\">")
      for (it in getCurrentRows(result)) {
        printStream.println(it)
      }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private fun writeExceedLatestFound(
    printStream: OutputStream,
    result: Result,
  ) {
    val versions = result.exceeded.dependencies
    if (versions.isNotEmpty()) {
      // The following dependencies exceed the version found at the "
      //        + revision + " revision level:
      printStream.println("<h2>Exceeded dependencies</h2>")
      printStream.println(
        "<p>The following dependencies exceed the version found at the $revision revision level:<p>",
      )
      printStream.println("<table class=\"warningInfo\">")
      for (it in getExceededRows(result)) {
        printStream.println(it)
      }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private fun writeUpgrades(
    printStream: OutputStream,
    result: Result,
  ) {
    val versions = result.outdated.dependencies
    if (versions.isNotEmpty()) {
      printStream.println("<h2>Later dependencies</h2>")
      printStream.println("<p>The following dependencies have later $revision versions:<p>")
      printStream.println("<table class=\"warningInfo\">")
      for (it in getUpgradesRows(result)) {
        printStream.println(it)
      }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private fun writeUndeclared(
    printStream: OutputStream,
    result: Result,
  ) {
    val versions = result.undeclared.dependencies
    if (versions.isNotEmpty()) {
      printStream.println("<h2>Undeclared dependencies</h2>")
      printStream.println(
        "<p>Failed to compare versions for the following dependencies because they were declared without version:<p>",
      )
      printStream.println("<table class=\"warningInfo\">")
      for (row in getUndeclaredRows(result)) {
        printStream.println(row)
      }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private fun writeUnresolved(
    printStream: OutputStream,
    result: Result,
  ) {
    val versions = result.unresolved.dependencies
    if (versions.isNotEmpty()) {
      printStream.println("<h2>Unresolved dependencies</h2>")
      printStream
        .println("<p>Failed to determine the latest version for the following dependencies:<p>")
      printStream.println("<table class=\"warningInfo\">")
      for (it in getUnresolvedRows(result)) {
        printStream.println(it)
      }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private fun writeGradleUpdates(
    printStream: OutputStream,
    result: Result,
  ) {
    if (!result.gradle.enabled) {
      return
    }
    printStream.println("<h2>Gradle $gradleReleaseChannel updates</h2>")
    printStream.println("Gradle $gradleReleaseChannel updates:")
    // Log Gradle update checking failures.
    if (result.gradle.current.isFailure) {
      printStream.println(
        "<p>[ERROR] [release channel: ${CURRENT.id}] " + result.gradle.current.reason + "</p>",
      )
    }
    if ((gradleReleaseChannel == RELEASE_CANDIDATE.id || gradleReleaseChannel == NIGHTLY.id) &&
      result.gradle.releaseCandidate.isFailure
    ) {
      printStream.println(
        "<p>[ERROR] [release channel: ${RELEASE_CANDIDATE.id}] " + result.gradle.releaseCandidate.reason + "</p>",
      )
    }
    if (gradleReleaseChannel == NIGHTLY.id && result.gradle.nightly.isFailure) {
      printStream.println(
        "<p>[ERROR] [release channel: ${NIGHTLY.id}] " + result.gradle.nightly.reason + "</p>",
      )
    }

    // print Gradle updates in breadcrumb format
    printStream.print("<p>Gradle: [" + getGradleVersionUrl(result.gradle.running.version))
    var updatePrinted = false
    if (result.gradle.current.isUpdateAvailable && result.gradle.current > result.gradle.running) {
      updatePrinted = true
      printStream.print(" -> " + getGradleVersionUrl(result.gradle.current.version))
    }
    if ((gradleReleaseChannel == RELEASE_CANDIDATE.id || gradleReleaseChannel == NIGHTLY.id) &&
      result.gradle.releaseCandidate.isUpdateAvailable &&
      result.gradle.releaseCandidate >
      result.gradle.current
    ) {
      updatePrinted = true
      printStream.print(" -> " + getGradleVersionUrl(result.gradle.releaseCandidate.version))
    }
    if (gradleReleaseChannel == NIGHTLY.id &&
      result.gradle.nightly.isUpdateAvailable &&
      result.gradle.nightly >
      result.gradle.current
    ) {
      updatePrinted = true
      printStream.print(" -> " + getGradleVersionUrl(result.gradle.nightly.version))
    }
    if (!updatePrinted) {
      printStream.print(": UP-TO-DATE")
    }
    printStream.println("]</p>")
    printStream.println(getGradleUrl())
  }

  private fun getUpgradesRows(result: Result): List<String> {
    val rows = mutableListOf<String>()
    val list = result.outdated
    rows.add(
      "<tr class=\"header\"><th colspan=\"6\"><b>Later dependencies<span>(Click to collapse)</span></b></th></tr>",
    )
    rows.add(
      """
      <tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td>
      <b>Current Version</b></td><td><b>Latest Version</b></td><td><b>Reason</b></td></tr>""".trim(),
    )
    for (dependency in list.dependencies) {
      val rowStringFmt =
        "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      val rowString =
        String.format(
          rowStringFmt,
          dependency.name.orEmpty(),
          dependency.group.orEmpty(),
          getUrlString(dependency.projectUrl),
          getVersionString(dependency.group.orEmpty(), dependency.name.orEmpty(), dependency.version),
          getVersionString(
            dependency.group.orEmpty(),
            dependency.name.orEmpty(),
            getDisplayableVersion(dependency.available),
          ),
          dependency.userReason.orEmpty(),
        )
      rows.add(rowString)
    }
    return rows
  }

  private fun getDisplayableVersion(versionAvailable: VersionAvailable): String? {
    if (revision.equals("milestone", ignoreCase = true)) {
      return versionAvailable.milestone
    } else if (revision.equals("release", ignoreCase = true)) {
      return versionAvailable.release
    } else if (revision.equals("integration", ignoreCase = true)) {
      return versionAvailable.integration
    }
    return ""
  }

  override fun getFileExtension(): String {
    return "html"
  }

  companion object {
    private const val HEADER = """
    <head>
    <title>Project Dependency Updates Report</title>
    <style>
       .body {
           font:100% verdana, arial, sans-serif;
           background-color:#fff
       }
       .currentInfo {
           border-collapse: collapse;
       }
       .currentInfo header {
           cursor:pointer;
           padding: 12px 15px;
       }
       .currentInfo td {
           border: 1px solid black;
           padding: 12px 15px;
           border-collapse: collapse;
       }
       .currentInfo tr:nth-child(even) {
           background-color: #E4FFB7;
           padding: 12px 15px;
           border-collapse: collapse;
       }
       .currentInfo tr:nth-child(odd) {
           background-color: #EFFFD2;
           padding: 12px 15px;
           border-collapse: collapse;
       }
       .warningInfo {
           border-collapse: collapse;
       }
       .warningInfo header {
           cursor:pointer;
           padding: 12px 15px;
       }
       .warningInfo td {
           border: 1px solid black;
           padding: 12px 15px;
           border-collapse: collapse;
       }
       .warningInfo tr:nth-child(even) {
           background-color: #FFFF66;
           padding: 12px 15px;
           border-collapse: collapse;
       }
       .warningInfo tr:nth-child(odd) {
           background-color: #FFFFCC;
           padding: 12px 15px;
           border-collapse: collapse;
       }
   </style>
   <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.5.1/jquery.min.js"></script>
   <script>
   $(document).ready(function(){
    /* set current to collapsed initially */
    $('#currentId').nextUntil('tr.header').slideToggle(100, function(){});
    /* click callback to toggle tables */
    $('tr.header').click(function(){
        $(this).find('span').text(function(_, value){return value=='(Click to collapse)'?'(Click to expand)':'(Click to collapse)'});
        $(this).nextUntil('tr.header').slideToggle(100, function(){
        });
    });
   });
   </script>
   </head>
   """

    private fun getCurrentRows(result: Result): List<String> {
      val rows = mutableListOf<String>()
      // The following dependencies are using the latest milestone version:
      val list = result.current
      rows.add(
        """
        <tr class="header" id="currentId"><th colspan="5">
        <b>Current dependencies<span>(Click to expand)</span></b></th></tr>""".trim(),
      )
      rows.add(
        "<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td><b>Reason</b></td></tr>",
      )
      for (dependency in list.dependencies) {
        val rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
        val rowString =
          String.format(
            rowStringFmt,
            dependency.name,
            dependency.group,
            getUrlString(dependency.projectUrl),
            // TODO nullness
            getVersionString(
              dependency.group.orEmpty(),
              dependency.name.orEmpty(),
              dependency.version,
            ),
            dependency.userReason.orEmpty(),
          )
        rows.add(rowString)
      }
      return rows
    }

    private fun getExceededRows(result: Result): List<String> {
      val rows = mutableListOf<String>()
      // The following dependencies are using the latest milestone version:
      val list = result.exceeded
      rows.add(
        "<tr class=\"header\"><th colspan=\"6\"><b>Exceeded dependencies<span>(Click to collapse)</span></b></th></tr>",
      )
      rows.add(
        """
        <tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td>
        <td><b>Current Version</b></td><td><b>Latest Version</b></td><td><b>Reason</b></td></tr>""".trim(),
      )
      for (dependency in list.dependencies) {
        val rowStringFmt =
          "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
        val rowString =
          String.format(
            rowStringFmt,
            dependency.name,
            dependency.group,
            getUrlString(dependency.projectUrl),
            getVersionString(
              dependency.group.orEmpty(),
              dependency.name.orEmpty(),
              dependency.version,
            ),
            getVersionString(
              dependency.group.orEmpty(),
              dependency.name.orEmpty(),
              dependency.latest,
            ),
            dependency.userReason.orEmpty(),
          )
        rows.add(rowString)
      }
      return rows
    }

    private fun getUndeclaredRows(result: Result): List<String> {
      val rows = mutableListOf<String>()
      rows.add(
        "<tr class=\"header\"><th colspan=\"2\"><b>Undeclared dependencies<span>(Click to collapse)</span></b></th></tr>",
      )
      rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td></tr>")
      for (dependency in result.undeclared.dependencies) {
        val rowString =
          String.format("<tr><td>%s</td><td>%s</td></tr>", dependency.name, dependency.group)
        rows.add(rowString)
      }
      return rows
    }

    private fun getUnresolvedRows(result: Result): List<String> {
      val rows = mutableListOf<String>()
      val list = result.unresolved
      rows.add(
        "<tr class=\"header\"><th colspan=\"5\"><b>Unresolved dependencies<span>(Click to collapse)</span></b></th></tr>",
      )
      rows.add(
        "<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td>Reason</td></tr>",
      )
      for (dependency in list.dependencies) {
        val rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
        val rowString =
          String.format(
            rowStringFmt,
            dependency.name,
            dependency.group,
            getUrlString(dependency.projectUrl),
            getVersionString(
              dependency.group.orEmpty(),
              dependency.name.orEmpty(),
              dependency.version,
            ),
            dependency.userReason.orEmpty(),
          )
        rows.add(rowString)
      }
      return rows
    }

    private fun getGradleUrl(): String {
      return "<p>For information about Gradle releases click <a target=\"_blank\" href=\"https://gradle.org/releases/\">here</a>.</p>"
    }

    private fun getGradleVersionUrl(version: String?): String {
      if (version == null) {
        return "https://gradle.org/releases/"
      }
      return String
        .format(
          "<a target=\"_blank\" href=\"https://docs.gradle.org/%s/release-notes.html\">%s</a>",
          version,
          version,
        )
    }

    private fun getUrlString(url: String?): String {
      if (url == null) {
        return ""
      }
      return String.format("<a target=\"_blank\" href=\"%s\">%s</a>", url, url)
    }

    private fun getVersionString(
      group: String,
      name: String,
      version: String?,
    ): String {
      val mvn = getMvnVersionString(group, name, version)
      return String.format("%s %s", version, mvn)
    }

    private fun getMvnVersionString(
      group: String,
      name: String,
      version: String?,
    ): String {
      // https://central.sonatype.com/artifact/com.azure/azure-core-http-netty/1.5.4
      if (version == null) {
        return ""
      }
      val versionUrl =
        String
          .format("https://central.sonatype.com/artifact/%s/%s/%s/bundle", group, name, version)
      return String.format("<a target=\"_blank\" href=\"%s\">%s</a>", versionUrl, "Sonatype")
    }
  }
}
