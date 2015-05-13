package com.google.auto.value.processor.escapevelocity;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class Node {
  final int lineNumber;

  Node(int lineNumber) {
    this.lineNumber = lineNumber;
  }

  public abstract Object evaluate(EvaluationContext context);

  static class EmptyNode extends Node {
    EmptyNode(int lineNumber) {
      super(lineNumber);
    }

    @Override public Object evaluate(EvaluationContext context) {
      return "";
    }
  }

  static final class EofNode extends EmptyNode {
    EofNode(int lineNumber) {
      super(lineNumber);
    }
  }

  static Node cons(Node lhs, Node rhs) {
    if (lhs instanceof EmptyNode) {
      return rhs;
    } else if (rhs instanceof EmptyNode) {
      return lhs;
    } else {
      return new Cons(lhs, rhs);
    }
  }

  private static final class Cons extends Node {
    private final Node lhs;
    private final Node rhs;

    Cons(Node lhs, Node rhs) {
      super(lhs.lineNumber);
      this.lhs = lhs;
      this.rhs = rhs;
    }

    @Override public Object evaluate(EvaluationContext context) {
      return String.valueOf(lhs.evaluate(context)) + String.valueOf(rhs.evaluate(context));
    }
  }
}
