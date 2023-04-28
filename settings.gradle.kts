plugins {
  `gradle-enterprise`
}

gradleEnterprise {
  buildScan {
    termsOfServiceUrl = "https://gradle.com/terms-of-service"
    termsOfServiceAgree = "yes"

    if (System.getenv("CI") == "true") {
      isUploadInBackground = false
      publishAlways()
    } else {
      obfuscation.ipAddresses { addresses -> emptyList() }
    }
    if (System.getenv("GITHUB_ACTIONS") == "true") {
      obfuscation.username { name -> "github" }
    }
  }
}

rootProject.name = "gradle-versions-plugin"

include(":gradle-versions-plugin")
