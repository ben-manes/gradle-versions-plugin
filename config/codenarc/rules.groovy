/*
* Copyright 2013 the original author or authors
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
ruleset {
  description 'Rules For Gradle Versions Plugin Project'
  ruleset('rulesets/basic.xml')
  ruleset('rulesets/braces.xml')
  ruleset('rulesets/concurrency.xml')
  ruleset('rulesets/convention.xml')
  ruleset('rulesets/design.xml') {
    AbstractClassWithoutAbstractMethod(enabled: false)
  }
  ruleset('rulesets/dry.xml') {
    DuplicateStringLiteral(enabled: false) // too much hassle
  }
  ruleset('rulesets/exceptions.xml')
  ruleset('rulesets/formatting.xml') {
    Indentation(spacesPerIndentLevel: 2)
    SpaceAroundMapEntryColon(characterAfterColonRegex: /\s/)
  }
  ruleset('rulesets/comments.xml') {
    ClassJavadoc(enabled: false)
  }
  // generic rules need to be configured to be active and useful
  //ruleset('rulesets/generic.xml')
  IllegalRegex {
    name = 'TODO'
    priority = 2
    regex = /TODO/
    description = 'TODOs should not be commited'
  }

  ruleset('rulesets/groovyism.xml') {
    GetterMethodCouldBeProperty(enabled: false)
  }
  ruleset('rulesets/imports.xml') {
    NoWildcardImports(enabled: false)
  }
  ruleset('rulesets/junit.xml')
  ruleset('rulesets/logging.xml')
  ruleset('rulesets/naming.xml') {
    FactoryMethodName(enabled: false)
  }
  ruleset('rulesets/security.xml') {
    JavaIoPackageAccess(enabled: false) // gradle uses Java.io.File
  }
  ruleset('rulesets/serialization.xml')

  ruleset('rulesets/unnecessary.xml') {
    UnnecessaryDefInMethodDeclaration(enabled: false)
    UnnecessaryGetter(enabled: false)
    UnnecessaryReturnKeyword(enabled: false)
    UnnecessaryCast(enabled: false) // buggy
  }
  ruleset('rulesets/unused.xml')
}
