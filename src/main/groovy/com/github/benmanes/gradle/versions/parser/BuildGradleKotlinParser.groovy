package com.github.benmanes.gradle.versions.parser

import groovy.transform.CompileStatic
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.*

@CompileStatic
class BuildGradleKotlinParser extends BuildGradleParser {

  private KtFile psi
  private KtBinaryExpression versionKtBinaryExpression

  BuildGradleKotlinParser(final File file) {
    super(file)
  }

  @Override
  String getVersionExpression() {
    return getVersionKtBinaryExpression() ? getVersionKtBinaryExpression().getText() : null
  }

  @Override
  String getVersionDefinition() {
    return getVersionKtBinaryExpression() ?
      getVersionKtBinaryExpression().getLastChild().getText().replaceAll(/['"]/, '') : null
  }

  private KtFile getPsi() {
    if (psi == null) {
      final CompilerConfiguration compilerConf = new CompilerConfiguration()
      compilerConf.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
        new PrintingMessageCollector(System.err, MessageRenderer.PLAIN_FULL_PATHS, false))
      Disposer.newDisposable().with { Disposable disposable ->
        final KotlinCoreEnvironment env = KotlinCoreEnvironment.createForProduction(
          disposable, compilerConf, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        psi = new KtPsiFactory(env.project).createFile(file.getName(), getContent())
      }
    }
    return psi
  }

  private KtBinaryExpression getVersionKtBinaryExpression() {
    if (versionKtBinaryExpression == null) {
      for (final KtExpression statement : getPsi().findChildByClass(KtScript).getBlockExpression().getStatements()) {
        if (statement instanceof KtScriptInitializer) {
          final PsiElement element = statement.getFirstChild()
          if (element instanceof KtBinaryExpression && element.getFirstChild().getText() == 'version') {
            versionKtBinaryExpression = (KtBinaryExpression) element
            break
          }
        }
      }
    }
    return versionKtBinaryExpression
  }
}
