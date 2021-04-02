package com.github.benmanes.gradle.versions.parser

import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.gradle.api.GradleException

/**
 * Parser for build.gradle files which are based on groovy syntax.
 */
@CompileStatic
class BuildGradleGroovyParser extends BuildGradleParser {

  private List<ASTNode> astNodes
  private Expression versionAstExpr
  private String versionDefinition, versionExpression

  BuildGradleGroovyParser(final File file) {
    super(file)
  }

  @Override
  String getVersionExpression() {
    if (!versionExpression) {
      if (getVersionAstExpr()) {
        versionExpression = ''
        final String[] contentLines = content.split('\\r?\\n')
        final String newLine = content.find('\r\n') ?: '\n'
        for (i in getVersionAstExpr().getLineNumber()..getVersionAstExpr().getLastLineNumber()) {
          int startPos = i == getVersionAstExpr().getLineNumber() ? getVersionAstExpr().getColumnNumber() - 1 : 0
          if (i == getVersionAstExpr().getLastLineNumber()) {
            versionExpression += contentLines[i - 1].substring(startPos, getVersionAstExpr().getLastColumnNumber() - 1)
          } else {
            versionExpression += contentLines[i - 1].substring(startPos) + newLine
          }
        }
      }
    }
    return versionExpression
  }

  @Override
  String getVersionDefinition() {
    if (!versionDefinition) {
      if (getVersionAstExpr()) {
        if (getVersionAstExpr() instanceof MethodCallExpression) {
          versionDefinition = ((MethodCallExpression) getVersionAstExpr()).getArguments().getText()
        } else if (getVersionAstExpr() instanceof BinaryExpression) {
          versionDefinition = ((BinaryExpression) getVersionAstExpr()).getRightExpression().getText()
        }
      }
    }
    return versionDefinition
  }

  private List<ASTNode> getAstNodes() {
    if (astNodes == null) {
      astNodes = (List<ASTNode>) new AstBuilder().buildFromString(content)
    }
    return astNodes
  }

  @TypeChecked(TypeCheckingMode.SKIP)
  private Expression getVersionAstExpr() {
    if (versionAstExpr == null) {
      final BlockStatement blockStatement = getAstNodes()[0] as BlockStatement
      blockStatement.getStatements().each { Statement statement ->
        if (statement instanceof ExpressionStatement || statement instanceof ReturnStatement) {
          final Expression expression = statement.getExpression()
          if ((expression instanceof MethodCallExpression && expression.getMethod().getText() == 'version')
            || (expression instanceof BinaryExpression && expression.getLeftExpression().getText() == 'version')) {
            versionAstExpr = expression
          }
        }
      }
      if (!versionAstExpr) {
        throw new GradleException("Could not locate version declaration in file ${file.getPath()}")
      }
    }
    return versionAstExpr
  }
}
