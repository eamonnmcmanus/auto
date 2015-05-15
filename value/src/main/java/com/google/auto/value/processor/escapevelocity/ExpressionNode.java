package com.google.auto.value.processor.escapevelocity;

import java.util.Collection;
import java.util.Map;

/**
 * A node in the parse tree representing an expression. Expressions appear only inside directives,
 * specifically {@code #set}, {@code #if}, {@code #foreach}, and macro calls.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class ExpressionNode extends Node {
  ExpressionNode(int lineNumber) {
    super(lineNumber);
  }

  /**
   * True if evaluating this expression yields a value that is considered true by Velocity's rules.
   * A value is false if it is null, or Boolean.FALSE, or an empty string or collection. Every other
   * value is true.
   */
  boolean isTrue(EvaluationContext context) {
    Object value = evaluate(context);
    if (value instanceof Boolean) {
      return (Boolean) value;
    } else if (value instanceof String) {
      return !((String) value).isEmpty();
    } else if (value instanceof Collection<?>) {
      return !((Collection<?>) value).isEmpty();
    } else if (value instanceof Map<?, ?>) {
      return !((Map<?, ?>) value).isEmpty();
    } else {
      return value != null;
    }
  }

  /**
   * The integer result of evaluating this expression.
   *
   * @throws EvaluationException if evaluating the expression produces an exception, or if it
   *     yields a value that is not an Integer.
   */
  int intValue(EvaluationContext context) {
    Object value = evaluate(context);
    if (!(value instanceof Integer)) {
      throw evaluationException("Arithmetic only available on integers, not " + show(value));
    }
    return (Integer) value;
  }

  /**
   * Returns a string representing the given value, for use in error messages. The string includes
   * both the value's {@code toString()} and its type.
   */
  private static String show(Object value) {
    if (value == null) {
      return "null";
    } else {
      return value + " (a " + value.getClass().getName() + ")";
    }
  }

  /**
   * Parent class of all binary expressions. In {@code #set ($a = $b + $c)}, this will be the type
   * of the node representing {@code $b + $c}.
   */
  private abstract static class BinaryExpressionNode extends ExpressionNode {
    final ExpressionNode lhs;
    final ExpressionNode rhs;

    BinaryExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs.lineNumber);
      this.lhs = lhs;
      this.rhs = rhs;
    }
  }

  /**
   * A node in the parse tree representing an expression like {@code $a || $b}.
   */
  static class OrExpressionNode extends BinaryExpressionNode {
    OrExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override Object evaluate(EvaluationContext context) {
      return lhs.isTrue(context) || rhs.isTrue(context);
    }
  }

  /**
   * A node in the parse tree representing an expression like {@code $a && $b}.
   */
  static class AndExpressionNode extends BinaryExpressionNode {
    AndExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override Object evaluate(EvaluationContext context) {
      return lhs.isTrue(context) && rhs.isTrue(context);
    }
  }

  /**
   * A node in the parse tree representing an expression like {@code $a == $b}. The expression
   * {@code $a != $b} is rewritten into {@code !($a == $b)} so it also ends up using this class.
   *
   * <p>Velocity's definition of equality differs depending on whether the objects being compared
   * are of the same class. If so, equality comes from {@code Object.equals} as you would expect.
   * But if they are not of the same class, they are considered equal if their {@code toString()}
   * values are equal. This means that integer 123 equals long 123L and also string {@code "123"}.
   * It also means that equality isn't always transitive. For example, two StringBuilder objects
   * each containing {@code "123"} will not compare equal, even though the string {@code "123"}
   * compares equal to each of them.
   */
  static class EqualsExpressionNode extends BinaryExpressionNode {
    EqualsExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override Object evaluate(EvaluationContext context) {
      Object lhsValue = lhs.evaluate(context);
      Object rhsValue = rhs.evaluate(context);
      if (lhsValue == rhsValue) {
        return true;
      }
      if (lhsValue == null || rhsValue == null) {
        return false;
      }
      if (lhsValue.getClass().equals(rhsValue.getClass())) {
        return lhsValue.equals(rhsValue);
      }
      // Funky equals behaviour specified by Velocity.
      return lhsValue.toString().equals(rhsValue.toString());
    }
  }

  /**
   * A node in the parse tree representing an expression like {@code $a < $b}. Other inequalities
   * are rewritten in terms of this one and possibly logical negation. For example {@code $a <= $b}
   * becomes {@code !($b < $a)}.
   */
  static class LessExpressionNode extends BinaryExpressionNode {
    LessExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override Object evaluate(EvaluationContext context) {
      Object lhsValue = lhs.evaluate(context);
      Object rhsValue = rhs.evaluate(context);
      for (Object value : new Object[] {lhsValue}) {
        if (!(value instanceof Comparable<?>)) {
          throw evaluationException("Not comparable: " + show(value));
        }
      }
      if (!lhsValue.getClass().equals(rhsValue.getClass())) {
        throw evaluationException("Cannot compare objects not of same class: "
            + show(lhsValue) + " versus " + show(rhsValue));
      }
      @SuppressWarnings({"rawtypes", "unchecked"})
      int compare = ((Comparable) lhsValue).compareTo(rhsValue);
      return compare < 0;
    }
  }

  /**
   * A node in the parse tree representing an expression like {@code $a + $b}, {@code $a - $b},
   * {@code $a * $b}, {@code $a / $b}, or {@code $a % $b}.
   */
  static class ArithmeticExpressionNode extends BinaryExpressionNode {
    private final int op;

    ArithmeticExpressionNode(int lineNumber, int op, ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
      this.op = op;
    }

    @Override Object evaluate(EvaluationContext context) {
      int lhsValue = lhs.intValue(context);
      int rhsValue = rhs.intValue(context);
      switch (op) {
        case '+':
          return lhsValue + rhsValue;
        case '-':
          return lhsValue - rhsValue;
        case '*':
          return lhsValue * rhsValue;
        case '/':
          if (rhsValue == 0) {
            throw evaluationException("Division by 0");
          }
          return lhsValue / rhsValue;
        case '%':
          if (rhsValue == 0) {
            throw evaluationException("Division by 0");
          }
          return lhsValue % rhsValue;
        default:
          throw new AssertionError(op);
      }
    }
  }

  /**
   * A node in the parse tree representing an expression like {@code !$a}.
   */
  static class NotExpressionNode extends ExpressionNode {
    private final ExpressionNode expr;

    NotExpressionNode(ExpressionNode expr) {
      super(expr.lineNumber);
      this.expr = expr;
    }

    @Override Object evaluate(EvaluationContext context) {
      return !expr.isTrue(context);
    }
  }
}
