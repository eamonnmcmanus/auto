package com.google.auto.value.processor.escapevelocity;

import java.util.Collection;
import java.util.Map;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class ExpressionNode extends Node {
  ExpressionNode(int lineNumber) {
    super(lineNumber);
  }

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

  int intValue(EvaluationContext context) {
    Object value = evaluate(context);
    if (!(value instanceof Integer)) {
      throw evaluationException("Arithmetic only available on integers, not " + show(value));
    }
    return (Integer) value;
  }

  EvaluationException evaluationException(String message) {
    return new EvaluationException("In expression on line " + lineNumber + ": " + message);
  }

  static String show(Object value) {
    if (value == null) {
      return "null";
    } else {
      return value + " (a " + value.getClass().getName() + ")";
    }
  }

  static class ConstantExpressionNode extends ExpressionNode {
    private final Object value;

    ConstantExpressionNode(int lineNumber, Object value) {
      super(lineNumber);
      this.value = value;
    }

    @Override public Object evaluate(EvaluationContext context) {
      return value;
    }
  }

  private abstract static class BinaryExpressionNode extends ExpressionNode {
    final ExpressionNode lhs;
    final ExpressionNode rhs;

    BinaryExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs.lineNumber);
      this.lhs = lhs;
      this.rhs = rhs;
    }
  }

  static class OrExpressionNode extends BinaryExpressionNode {
    OrExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override public Object evaluate(EvaluationContext context) {
      return lhs.isTrue(context) || rhs.isTrue(context);
    }
  }

  static class AndExpressionNode extends BinaryExpressionNode {
    AndExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override public Object evaluate(EvaluationContext context) {
      return lhs.isTrue(context) && rhs.isTrue(context);
    }
  }

  static class EqualsExpressionNode extends BinaryExpressionNode {
    EqualsExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override public Object evaluate(EvaluationContext context) {
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

  static class LessExpressionNode extends BinaryExpressionNode {
    LessExpressionNode(ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
    }

    @Override public Object evaluate(EvaluationContext context) {
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

  static class ArithmeticExpressionNode extends BinaryExpressionNode {
    private final int op;

    ArithmeticExpressionNode(int lineNumber, int op, ExpressionNode lhs, ExpressionNode rhs) {
      super(lhs, rhs);
      this.op = op;
    }

    @Override public Object evaluate(EvaluationContext context) {
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

  static class NotExpressionNode extends ExpressionNode {
    private final ExpressionNode expr;

    NotExpressionNode(ExpressionNode expr) {
      super(expr.lineNumber);
      this.expr = expr;
    }

    @Override public Object evaluate(EvaluationContext context) {
      return !expr.isTrue(context);
    }
  }

  static class NegateExpressionNode extends ExpressionNode {
    private final ExpressionNode expr;

    NegateExpressionNode(ExpressionNode expr) {
      super(expr.lineNumber);
      this.expr = expr;
    }

    @Override public Object evaluate(EvaluationContext context) {
      return -expr.intValue(context);
    }
  }
}
