package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableList;

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
   * Returns an empty node in the parse tree. This is used for example to represent the trivial
   * "else" part of an {@code #if} that does not have an explicit {@code #else}.
   */
  static Node emptyNode(int lineNumber) {
    return new Cons(lineNumber, ImmutableList.<Node>of());
  }

  /**
   * A synthetic node that represents the end of the input. This node is the last one in the
   * initial token string and also the last one in the parse tree.
   */
  static final class EofNode extends Node {
    EofNode(int lineNumber) {
      super(lineNumber);
    }

    @Override
    Object evaluate(EvaluationContext context) {
      return "";
    }
  }

  /**
   * Create a new parse tree node that is the concatenation of the two given ones. Evaluating the
   * new node produces the same string as evaluating the two given nodes and concatenating the
   * result.
   */
  static Node cons(Node lhs, Node rhs) {
    ImmutableList.Builder<Node> nodes = ImmutableList.builder();
    for (Node node : new Node[] {lhs, rhs}) {
      // Special-casing cons-of-cons avoids deep recursion when we have many consecutive
      // concatenated nodes. Building up a tree of two-by-two conses would otherwise imply
      // recursion to the depth of the tree when evaluating it.
      if (node instanceof Cons) {
        nodes.addAll(((Cons) node).nodes);
      } else {
        nodes.add(node);
      }
    }
    return new Cons(lhs.lineNumber, nodes.build());
  }

  private static final class Cons extends Node {
    private final ImmutableList<Node> nodes;

    Cons(int lineNumber, ImmutableList<Node> nodes) {
      super(lineNumber);
      this.nodes = nodes;
    }

    @Override Object evaluate(EvaluationContext context) {
      StringBuilder sb = new StringBuilder();
      for (Node node : nodes) {
        sb.append(node.evaluate(context));
      }
      return sb.toString();
    }
  }
}
