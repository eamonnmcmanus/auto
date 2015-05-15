package com.google.auto.value.processor.escapevelocity;

/**
 * A node in the parse tree.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class Node {
  final int lineNumber;

  Node(int lineNumber) {
    this.lineNumber = lineNumber;
  }

  /**
   * Returns the result of evaluating this node in the given context. This result may be used as
   * part of a further operation, for example evaluating {@code 2 + 3} to 5 in order to set
   * {@code $x} to 5 in {@code #set ($x = 2 + 3)}. Or it may be used directly as part of the
   * template output, for example evaluating replacing {@code name} by {@code Fred} in
   * {@code My name is $name.}.
   */
  abstract Object evaluate(EvaluationContext context);

  EvaluationException evaluationException(String message) {
    return new EvaluationException("In expression on line " + lineNumber + ": " + message);
  }

  EvaluationException evaluationException(Throwable cause) {
    return new EvaluationException("In expression on line " + lineNumber + ": " + cause, cause);
  }

  /**
   * An empty node in the parse tree. This is used for example to represent the trivial "else"
   * part of an {@code #if} that does not have an explicit {@code #else}.
   */
  static class EmptyNode extends Node {
    EmptyNode(int lineNumber) {
      super(lineNumber);
    }

    @Override Object evaluate(EvaluationContext context) {
      return "";
    }
  }

  /**
   * A synthetic node that represents the end of the input. This node is the last one in the
   * initial token string and also the last one in the parse tree.
   */
  static final class EofNode extends EmptyNode {
    EofNode(int lineNumber) {
      super(lineNumber);
    }
  }

  /**
   * Create a new parse tree node that is the concatenation of the two given ones. Evaluating the
   * new node produces the same string as evaluating the two given nodes and concatenating the
   * result.
   */
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

    @Override Object evaluate(EvaluationContext context) {
      return String.valueOf(lhs.evaluate(context)) + String.valueOf(rhs.evaluate(context));
    }
  }
}
