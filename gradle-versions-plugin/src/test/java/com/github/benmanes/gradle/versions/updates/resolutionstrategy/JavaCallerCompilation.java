package com.github.benmanes.gradle.versions.updates.resolutionstrategy;

/**
 * Compiling this file is the test. An implicitly-typed Java lambda is not pertinent to
 * applicability, so if the {@code ComponentSelectionWithCurrent.() -> Unit} overloads of
 * {@code all} and {@code withModule} lose their {@code @JvmSynthetic}, javac sees both them and
 * the {@code Action} overloads, finds neither more specific, and fails with
 * "reference to all is ambiguous".
 */
final class JavaCallerCompilation {
  private JavaCallerCompilation() {}

  static void callsAll(ComponentSelectionRulesWithCurrent rules) {
    rules.all(selection -> selection.reject("nope"));
  }

  static void callsWithModule(ComponentSelectionRulesWithCurrent rules) {
    rules.withModule("com.example:example", selection -> selection.reject("nope"));
  }
}
