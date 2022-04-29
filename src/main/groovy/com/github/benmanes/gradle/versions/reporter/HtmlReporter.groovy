package com.github.benmanes.gradle.versions.reporter

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

import com.github.benmanes.gradle.versions.reporter.result.DependenciesGroup
import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.DependencyLatest
import com.github.benmanes.gradle.versions.reporter.result.DependencyOutdated
import com.github.benmanes.gradle.versions.reporter.result.DependencyUnresolved
import com.github.benmanes.gradle.versions.reporter.result.Result
import com.github.benmanes.gradle.versions.reporter.result.VersionAvailable
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/**
 * An html reporter for the dependency updates results.
 */
@CompileStatic
@TupleConstructor(callSuper = true, includeSuperProperties = true, includeSuperFields = true)
class HtmlReporter extends AbstractReporter {

  String header = """
    <!DOCTYPE html>
    <HEAD><TITLE>Project Dependency Updates Report</TITLE></HEAD>
    <style type=\"text/css\">
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
   <script src=\"https://ajax.googleapis.com/ajax/libs/jquery/3.5.1/jquery.min.js\"></script>

   <script type="text/javascript">
   \$(document).ready(function(){
    /* set current to collapsed initially */
    \$('#currentId').nextUntil('tr.header').slideToggle(100, function(){});
    /* click callback to toggle tables */
    \$('tr.header').click(function(){
        \$(this).find('span').text(function(_, value){return value=='(Click to collapse)'?'(Click to expand)':'(Click to collapse)'});
        \$(this).nextUntil('tr.header').slideToggle(100, function(){
        });
    });
});
   </script>
   """

  @Override
  void write(Appendable printStream, Result result) {
    writeHeader(printStream)

    if (result.count == 0) {
      printStream.println('<P>No dependencies found.</P>')
    } else {
      writeUpToDate(printStream, result)
      writeExceedLatestFound(printStream, result)
      writeUpgrades(printStream, result)
      writeUndeclared(printStream, result)
      writeUnresolved(printStream, result)
    }

    writeGradleUpdates(printStream, result)
  }

  private void writeHeader(Appendable printStream) {
    printStream.println(header.stripMargin())
  }

  private void writeUpToDate(Appendable printStream, Result result) {
    SortedSet<Dependency> versions = result.getCurrent().getDependencies()
    if (!versions.isEmpty()) {
      printStream.println("<H2>Current dependencies</H2>")
      printStream.println("<p>The following dependencies are using the latest ${revision} version:<p>")
      printStream.println("<table class=\"currentInfo\">")
      getCurrentRows(result).each { printStream.println(it) }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private static List<String> getCurrentRows(Result result) {
    List<String> rows = new ArrayList<>()
    // The following dependencies are using the latest milestone version:
    DependenciesGroup<Dependency> list = result.getCurrent()
    rows.add("<tr class=\"header\" id = \"currentId\" ><th colspan=\"4\"><b>Current dependencies<span>(Click to expand)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td><b>Reason</b></td></tr>")
    for (Dependency item : list.dependencies) {
      String rowString
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()), getVersionString(item.getGroup(), item.getName(), item.getVersion()),
        item.getUserReason() ?: '')
      rows.add(rowString)
    }
    return rows
  }

  private void writeExceedLatestFound(Appendable printStream, Result result) {
    SortedSet<DependencyLatest> versions = result.getExceeded().getDependencies()
    if (!versions.isEmpty()) {
      // The following dependencies exceed the version found at the '
      //        + revision + ' revision level:
      printStream.println("<H2>Exceeded dependencies</H2>")
      printStream.println("<p>The following dependencies exceed the version found at the ${revision} revision level:<p>")
      printStream.println("<table class=\"warningInfo\">")
      getExceededRows(result).each { printStream.println(it) }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private static List<String> getExceededRows(Result result) {
    List<String> rows = new ArrayList<>()
    // The following dependencies are using the latest milestone version:
    DependenciesGroup<DependencyLatest> list = result.getExceeded()
    rows.add("<tr class=\"header\"><th colspan=\"5\"><b>Exceeded dependencies<span>(Click to collapse)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td><b>Latest Version</b></td><td><b>Reason</b></td></tr>")
    for (DependencyLatest item : list.dependencies) {
      String rowString
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()), getVersionString(item.getGroup(), item.getName(), item.getVersion()),
        getVersionString(item.getGroup(), item.getName(), item.getVersion()),
        item.getUserReason() ?: '')
      rows.add(rowString)
    }
    return rows
  }

  private void writeUpgrades(Appendable printStream, Result result) {
    SortedSet<DependencyOutdated> versions = result.getOutdated().getDependencies()
    if (!versions.isEmpty()) {
      printStream.println("<H2>Later dependencies</H2>")
      printStream.println("<p>The following dependencies have later ${revision} versions:<p>")
      printStream.println("<table class=\"warningInfo\">")
      getUpgradesRows(result).each { printStream.println(it) }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private List<String> getUpgradesRows(Result result) {
    List<String> rows = new ArrayList<>()
    DependenciesGroup<DependencyOutdated> list = result.getOutdated()
    rows.add("<tr class=\"header\"><th colspan=\"5\"><b>Later dependencies<span>(Click to collapse)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td><b>Latest Version</b></td><td><b>Reason</b></td></tr>")
    for (DependencyOutdated item : list.dependencies) {
      String rowString
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()), getVersionString(item.getGroup(), item.getName(), item.getVersion()),
        getVersionString(item.getGroup(), item.getName(), getDisplayableVersion(item.getAvailable())),
        item.getUserReason() ?: '')
      rows.add(rowString)
    }
    return rows
  }

  private static void writeUndeclared(Appendable printStream, Result result) {
    SortedSet<Dependency> versions = result.undeclared.dependencies
    if (!versions.empty) {
      printStream.println("<H2>Undeclared dependencies</H2>")
      printStream.println("<p>Failed to compare versions for the following dependencies because they were declared without version:<p>")
      printStream.println("<table class=\"warningInfo\">")
      getUndeclaredRows(result).each { String row -> printStream.println(row) }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private static List<String> getUndeclaredRows(Result result) {
    List<String> rows = new ArrayList<>()
    rows.add("<tr class=\"header\"><th colspan=\"2\"><b>Undeclared dependencies<span>(Click to collapse)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td></tr>")
    for (Dependency item : result.undeclared.dependencies) {
      String rowString
      rowString = String.format("<tr><td>%s</td><td>%s</td></tr>", item.name, item.group)
      rows.add(rowString)
    }
    return rows
  }

  private String getDisplayableVersion(VersionAvailable versionAvailable) {
    if (getRevision().equalsIgnoreCase("milestone")) {
      return versionAvailable.getMilestone()
    } else if (getRevision().equalsIgnoreCase("release")) {
      return versionAvailable.getRelease()
    } else if (getRevision().equalsIgnoreCase("integration")) {
      return versionAvailable.getIntegration()
    }
    return ""
  }

  private static void writeUnresolved(Appendable printStream, Result result) {
    SortedSet<DependencyUnresolved> versions = result.getUnresolved().getDependencies()
    if (!versions.isEmpty()) {
      printStream.println("<H2>Unresolved dependencies</H2>")
      printStream.println("<p>Failed to determine the latest version for the following dependencies:<p>")
      printStream.println("<table class=\"warningInfo\">")
      getUnresolvedRows(result).each { printStream.println(it) }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private static List<String> getUnresolvedRows(Result result) {
    List<String> rows = new ArrayList<>()
    DependenciesGroup<DependencyUnresolved> list = result.getUnresolved()
    rows.add("<tr class=\"header\"><th colspan=\"4\"><b>Unresolved dependencies<span>(Click to collapse)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td>Reason</td></tr>")
    for (DependencyUnresolved item : list.dependencies) {
      String rowString
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()), getVersionString(item.getGroup(), item.getName(), item.getVersion()),
        item.getUserReason() ?: '')
      rows.add(rowString)
    }
    return rows
  }

  private void writeGradleUpdates(Appendable printStream, Result result) {
    if (!result.gradle.isEnabled()) {
      return
    }

    printStream.println("<H2>Gradle ${gradleReleaseChannel} updates</H2>")

    printStream.println("Gradle ${gradleReleaseChannel} updates:")
    result.gradle.with {
      // Log Gradle update checking failures.
      if (current.isFailure) {
        printStream.println("<P>[ERROR] [release channel: ${CURRENT.id}] " + current.reason + "</P>")
      }
      if ((gradleReleaseChannel == RELEASE_CANDIDATE.id || gradleReleaseChannel == NIGHTLY.id) && releaseCandidate.isFailure) {
        printStream.println("<P>[ERROR] [release channel: ${RELEASE_CANDIDATE.id}] " + releaseCandidate.reason + "</P>")
      }
      if (gradleReleaseChannel == NIGHTLY.id && nightly.isFailure) {
        printStream.println("<P>[ERROR] [release channel: ${NIGHTLY.id}] " + nightly.reason + "</P>")
      }

      // print Gradle updates in breadcrumb format
      printStream.print("<P>Gradle: [" + getGradleVersionUrl(running.version))
      boolean updatePrinted = false
      if (current.isUpdateAvailable && current > running) {
        updatePrinted = true
        printStream.print(" -> " + getGradleVersionUrl(current.version))
      }
      if ((gradleReleaseChannel == RELEASE_CANDIDATE.id || gradleReleaseChannel == NIGHTLY.id) && releaseCandidate.isUpdateAvailable && releaseCandidate > current) {
        updatePrinted = true
        printStream.print(" -> " + getGradleVersionUrl(releaseCandidate.version))
      }
      if (gradleReleaseChannel == NIGHTLY.id && nightly.isUpdateAvailable && nightly > current) {
        updatePrinted = true
        printStream.print(" -> " + getGradleVersionUrl(nightly.version))
      }
      if (!updatePrinted) {
        printStream.print(": UP-TO-DATE")
      }
      printStream.println("]<P>")
      printStream.println(getGradleUrl())
    }
  }

  private static String getGradleUrl() {
    return "<P>For information about Gradle releases click <a target=\"_blank\" href=\"https://gradle.org/releases/\">here</a>."
  }

  private static String getGradleVersionUrl(String version) {
    if (version == null) {
      return "https://gradle.org/releases/"
    }
    return String.format("<a target=\"_blank\" href=\"https://docs.gradle.org/%s/release-notes.html\">%s</a>", version, version)
  }

  private static String getUrlString(String url) {
    if (url == null) {
      return ""
    }
    return String.format("<a target=\"_blank\" href=\"%s\">%s</a>", url, url)
  }

  private static String getVersionString(String group, String name, String version) {
    String mvn = getMvnVersionString(group, name, version)
    return String.format("%s %s", version, mvn)
  }

  private static String getMvnVersionString(String group, String name, String version) {
    // https://search.maven.org/artifact/com.azure/azure-core-http-netty/1.5.4
    if (version == null) {
      return ""
    }
    String versionUrl = String.format("https://search.maven.org/artifact/%s/%s/%s/bundle", group, name, version)
    return String.format("<a target=\"_blank\" href=\"%s\">%s</a>", versionUrl, "Sonatype")
  }

  @Override
  String getFileExtension() {
    return 'html'
  }
}
