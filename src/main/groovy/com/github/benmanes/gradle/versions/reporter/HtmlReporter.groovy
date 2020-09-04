package com.github.benmanes.gradle.versions.reporter

import com.github.benmanes.gradle.versions.reporter.result.DependenciesGroup
import com.github.benmanes.gradle.versions.reporter.result.Dependency
import com.github.benmanes.gradle.versions.reporter.result.DependencyLatest
import com.github.benmanes.gradle.versions.reporter.result.DependencyOutdated
import com.github.benmanes.gradle.versions.reporter.result.DependencyUnresolved
import com.github.benmanes.gradle.versions.reporter.result.Result
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.CURRENT
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.NIGHTLY
import static com.github.benmanes.gradle.versions.updates.gradle.GradleReleaseChannel.RELEASE_CANDIDATE

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
  def write(printStream, Result result) {
    writeHeader(printStream)

    if (result.count == 0) {
      printStream.println '<P>No dependencies found.</P>'
    } else {
      writeUpToDate(printStream, result)
      writeExceedLatestFound(printStream, result)
      writeUpgrades(printStream, result)
      writeUnresolved(printStream, result)
    }

    writeGradleUpdates(printStream, result)
  }

  private def writeHeader(printStream) {
    printStream.println header.stripMargin()
  }

  private def writeUpToDate(printStream, Result result) {
    SortedSet<Dependency> versions = result.getCurrent().getDependencies()
    if (!versions.isEmpty()) {
      printStream.println("<H2>Current dependencies</H2>")
      printStream.println("<p>The following dependencies are using the latest ${revision} version:<p>")
      printStream.println("<table class=\"currentInfo\">")
      getCurrentRows(result).each { printStream.println it }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private def getCurrentRows(Result result) {
    List<String> rows = new ArrayList<>();
    // The following dependencies are using the latest milestone version:
    DependenciesGroup<Dependency> list = result.getCurrent();
    rows.add("<tr class=\"header\" id = \"currentId\" ><th colspan=\"4\"><b>Current dependencies<span>(Click to expand)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td></tr>")
    for (Dependency item : list.dependencies) {
      String rowString;
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()), getVersionString(item.getGroup(), item.getName(), getDisplayableVersion(item)))
      rows.add(rowString)
    }
    return rows
  }

  private def writeExceedLatestFound(printStream, Result result) {
    SortedSet<DependencyLatest> versions = result.getExceeded().getDependencies();
    if (!versions.isEmpty()) {
      // The following dependencies exceed the version found at the '
      //        + revision + ' revision level:
      printStream.println("<H2>Exceeded dependencies</H2>")
      printStream.println("<p>The following dependencies exceed the version found at the ${revision} revision level:<p>")
      printStream.println("<table class=\"warningInfo\">")
      getExceededRows(result).each { printStream.println it }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private def getExceededRows(Result result) {
    List<String> rows = new ArrayList<>();
    // The following dependencies are using the latest milestone version:
    DependenciesGroup<DependencyLatest> list = result.getExceeded()
    rows.add("<tr class=\"header\"><th colspan=\"5\"><b>Exceeded dependencies<span>(Click to collapse)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td><b>Latest Version</b></td></tr>")
    for (DependencyLatest item : list.dependencies) {
      String rowString;
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()), getVersionString(item.getGroup(), item.getName(), item.getVersion()),
        getVersionString(item.getGroup(), item.getName(), getDisplayableVersion(item)))
      rows.add(rowString)
    }
    return rows
  }

  private def writeUpgrades(printStream, Result result) {
    SortedSet<DependencyOutdated> versions = result.getOutdated().getDependencies();
    if (!versions.isEmpty()) {
      printStream.println("<H2>Later dependencies</H2>")
      printStream.println("<p>The following dependencies have later ${revision} versions:<p>")
      printStream.println("<table class=\"warningInfo\">")
      getUpgradesRows(result).each { printStream.println it }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private def getUpgradesRows(Result result) {
    List<String> rows = new ArrayList<>();
    DependenciesGroup<DependencyOutdated> list = result.getOutdated()
    rows.add("<tr class=\"header\"><th colspan=\"5\"><b>Later dependencies<span>(Click to collapse)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td><td><b>Latest Version</b></td></tr>")
    for (DependencyOutdated item : list.dependencies) {
      String rowString;
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()), getVersionString(item.getGroup(), item.getName(), item.getVersion()),
        getVersionString(item.getGroup(), item.getName(), getDisplayableVersion(item)))
      rows.add(rowString)
    }
    return rows
  }

  private def String getDisplayableVersion(DependencyOutdated item) {
    if (getRevision().equalsIgnoreCase("release-candidate")) {
      return item.getAvailable()
    } else if (getRevision().equalsIgnoreCase("release")) {
      return item.getVersion()
    }
    return ""
  }

  private def String getDisplayableVersion(Dependency item) {
      return item.getVersion()
  }

  private def writeUnresolved(printStream, Result result) {
    SortedSet<DependencyUnresolved> versions = result.getUnresolved().getDependencies();
    if (!versions.isEmpty()) {
      printStream.println("<H2>Unresolved dependencies</H2>")
      printStream.println("<p>Failed to determine the latest version for the following dependencies:<p>")
      printStream.println("<table class=\"warningInfo\">")
      getUnresolvedRows(result).each { printStream.println it }
      printStream.println("</table>")
      printStream.println("<br>")
    }
  }

  private def getUnresolvedRows(Result result) {
    List<String> rows = new ArrayList<>();
    DependenciesGroup<DependencyUnresolved> list = result.getUnresolved()
    rows.add("<tr class=\"header\"><th colspan=\"4\"><b>Unresolved dependencies<span>(Click to collapse)</span></b></th></tr>")
    rows.add("<tr><td><b>Name</b></td><td><b>Group</b></td><td><b>URL</b></td><td><b>Current Version</b></td></tr>")
    for (DependencyUnresolved item : list.dependencies) {
      String rowString;
      String rowStringFmt = "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
      rowString = String.format(rowStringFmt, item.getName(), item.getGroup(),
        getUrlString(item.getProjectUrl()) , getVersionString(item.getGroup(), item.getName(), getDisplayableVersion(item)))
      rows.add(rowString)
    }
    return rows
  }

  private def writeGradleUpdates(printStream, Result result) {
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

  private def getGradleUrl() {
    return "<P>For information about Gradle releases click <a target=\"_blank\" href=\"https://gradle.org/releases/\">here</a>.";
  }

  private def getGradleVersionUrl(String version) {
    if (version == null) {
      return "https://gradle.org/releases/";
    }
    return String.format("<a target=\"_blank\" href=\"https://docs.gradle.org/%s/release-notes.html\">%s</a>", version, version)
  }

  private def getUrlString(String url) {
    if (url == null) {
      return "";
    }
    return String.format("<a target=\"_blank\" href=\"%s\">%s</a>", url, url)
  }

  private def getVersionString(String group, String name, String version) {
    String mvn = getMvnVersionString(group, name, version)
    String bintray = getBintrayVersionString(group, name, version)
    return String.format("%s %s %s", version, mvn, bintray)
  }

  private def getMvnVersionString(String group, String name, String version) {
    // https://mvnrepository.com/artifact/com.azure/azure-core-http-netty/1.5.4
    if (version == null) {
      return "";
    }
    String versionUrl = String.format("https://mvnrepository.com/artifact/%s/%s/%s", group, name, version)
    return String.format("<a target=\"_blank\" href=\"%s\">%s</a>", versionUrl, "Mvn")
  }

  private def getBintrayVersionString(String group, String name, String version) {
    // https://bintray.com/bintray/jcenter/com.azure%3Aazure-sdk-template/1.0.3
    if (version == null) {
      return "";
    }
    String versionUrl = String.format("https://bintray.com/bintray/jcenter/%s%%3A%s/%s", group, name, version)
    return String.format("<a target=\"_blank\" href=\"%s\">%s</a>", versionUrl, "Bintray")
  }

  @Override
  def getFileExtension() {
    return 'html'
  }
}
