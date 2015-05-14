package com.google.auto.value.processor.escapevelocity;

/**
 * A node in the parse tree representing a constant value. Evaluating the node yields the constant
 * value. Instances of this class are used both in expressions, like the {@code 23} in
 * {@code #set ($x = 23)}, and for literal text in templates. In the template...
 * <pre>{@code
 * abc#{if}($x == 5)def#{end}xyz
 * }</pre>
 * ...each of the strings {@code abc}, {@code def}, {@code xyz} is represented by an instance of
 * this class that {@linkplain #evaluate evaluates} to that string.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class ConstantExpressionNode extends ExpressionNode {
  private final Object value;

  ConstantExpressionNode(int lineNumber, Object value) {
    super(lineNumber);
    this.value = value;
  }

  @Override
  Object evaluate(EvaluationContext context) {
    return value;
  }
}
