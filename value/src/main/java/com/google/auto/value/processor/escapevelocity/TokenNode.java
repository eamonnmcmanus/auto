package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class TokenNode extends Node {
  TokenNode(int lineNumber) {
    super(lineNumber);
  }

  @Override Object evaluate(EvaluationContext vars) {
    throw new UnsupportedOperationException();
  }

  abstract String name();

  static final class EndTokenNode extends TokenNode {
    EndTokenNode(int lineNumber) {
      super(lineNumber);
    }

    @Override String name() {
      return "#end";
    }
  }

  abstract static class IfOrElseIfTokenNode extends TokenNode {
    final ExpressionNode condition;

    IfOrElseIfTokenNode(ExpressionNode condition) {
      super(condition.lineNumber);
      this.condition = condition;
    }
  }

  static final class IfTokenNode extends IfOrElseIfTokenNode {
    IfTokenNode(ExpressionNode condition) {
      super(condition);
    }

    @Override String name() {
      return "#if";
    }
  }

  static final class ElseIfTokenNode extends IfOrElseIfTokenNode {
    ElseIfTokenNode(ExpressionNode condition) {
      super(condition);
    }

    @Override String name() {
      return "#elseif";
    }
  }

  static final class ElseTokenNode extends TokenNode {
    ElseTokenNode(int lineNumber) {
      super(lineNumber);
    }

    @Override String name() {
      return "#else";
    }
  }

  static final class ForEachTokenNode extends TokenNode {
    final String var;
    final ExpressionNode collection;

    ForEachTokenNode(String var, ExpressionNode collection) {
      super(collection.lineNumber);
      this.var = var;
      this.collection = collection;
    }

    @Override String name() {
      return "#foreach";
    }
  }

  static final class MacroDefinitionTokenNode extends TokenNode {
    final String name;
    final ImmutableList<String> parameterNames;

    MacroDefinitionTokenNode(int lineNumber, String name, List<String> parameterNames) {
      super(lineNumber);
      this.name = name;
      this.parameterNames = ImmutableList.copyOf(parameterNames);
    }

    @Override String name() {
      return "#macro(" + name + ")";
    }
  }
}
