package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.List;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class DirectiveNode extends Node {
  DirectiveNode(int lineNumber) {
    super(lineNumber);
  }

  static class IfNode extends DirectiveNode {
    private final ExpressionNode condition;
    private final Node truePart;
    private final Node falsePart;

    IfNode(int lineNumber, ExpressionNode condition, Node trueNode, Node falseNode) {
      super(lineNumber);
      this.condition = condition;
      this.truePart = trueNode;
      this.falsePart = falseNode;
    }

    @Override public Object evaluate(EvaluationContext context) {
      Node branch = condition.isTrue(context) ? truePart : falsePart;
      return branch.evaluate(context);
    }
  }

  static class ForEachNode extends DirectiveNode {
    private final String var;
    private final ExpressionNode collection;
    private final Node body;

    ForEachNode(int lineNumber, String var, ExpressionNode in, Node body) {
      super(lineNumber);
      this.var = var;
      this.collection = in;
      this.body = body;
    }

    @Override
    public Object evaluate(EvaluationContext context) {
      Object collectionValue = collection.evaluate(context);
      Iterable<?> iterable;
      if (collectionValue instanceof Iterable<?>) {
        iterable = (Iterable<?>) collectionValue;
      } else if (collectionValue instanceof Object[]) {
        iterable = Arrays.asList((Object[]) collectionValue);
      } else {
        throw new IllegalArgumentException("Not iterable: " + collectionValue);
      }
      Runnable undo = context.setVar(var, null);
      StringBuilder sb = new StringBuilder();
      for (Object x : iterable) {
        context.setVar(var, x);
        sb.append(body.evaluate(context));
      }
      undo.run();
      return sb.toString();
    }
  }

  static class SetNode extends DirectiveNode {
    private final String var;
    private final Node expression;

    SetNode(String var, Node expression) {
      super(expression.lineNumber);
      this.var = var;
      this.expression = expression;
    }

    @Override
    public Object evaluate(EvaluationContext context) {
      context.setVar(var, expression.evaluate(context));
      return "";
    }
  }

  static class MacroDefinitionNode extends DirectiveNode {
    private final String name;
    private final ImmutableList<String> parameterNames;
    private final Node body;

    MacroDefinitionNode(int lineNumber, String name, List<String> parameterNames, Node body) {
      super(lineNumber);
      this.name = name;
      this.parameterNames = ImmutableList.copyOf(parameterNames);
      this.body = body;
    }

    @Override public Object evaluate(EvaluationContext context) {
      context.defineMacro(new Macro(lineNumber, name, parameterNames, body));
      return "";
    }
  }

  static class MacroCallNode extends DirectiveNode {
    private final String name;
    private final ImmutableList<Node> argumentNodes;

    MacroCallNode(int lineNumber, String name, ImmutableList<Node> argumentNodes) {
      super(lineNumber);
      this.name = name;
      this.argumentNodes = argumentNodes;
    }

    @Override
    public Object evaluate(EvaluationContext context) {
      Macro macro = context.getMacro(name);
      if (macro == null) {
        throw new IllegalArgumentException(
            "#" + name + " on line " + lineNumber
                + " is neither a standard directive nor a macro that has been defined");
      }
      ImmutableList.Builder<Object> arguments = ImmutableList.builder();
      for (Node argumentNode : argumentNodes) {
        arguments.add(argumentNode.evaluate(context));
      }
      return macro.evaluate(context, arguments.build());
    }
  }
}
