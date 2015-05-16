package com.google.auto.value.processor.escapevelocity;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * A node in the parse tree that is a reference. A reference is anything beginning with {@code $},
 * such as {@code $x} or {@code $x[$i].foo($j)}.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class ReferenceNode extends ExpressionNode {
  ReferenceNode(int lineNumber) {
    super(lineNumber);
  }

  /**
   * A node in the parse tree that is a plain reference such as {@code $x}. This node may appear
   * inside a more complex reference like {@code $x.foo}.
   *
   * @author emcmanus@google.com (Éamonn McManus)
   */
  static class PlainReferenceNode extends ReferenceNode {
    final String id;

    PlainReferenceNode(int lineNumber, String id) {
      super(lineNumber);
      this.id = id;
    }

    @Override Object evaluate(EvaluationContext context) {
      if (context.varIsDefined(id)) {
        return context.getVar(id);
      } else {
        throw new EvaluationException("Undefined reference $" + id);
      }
    }
  }

  /**
   * A node in the parse tree that is a reference to a property of another reference, like
   * {@code $x.foo} or {@code $x[$i].foo}.
   */
  static class MemberReferenceNode extends ReferenceNode {
    final ReferenceNode lhs;
    final String id;

    MemberReferenceNode(ReferenceNode lhs, String id) {
      super(lhs.lineNumber);
      this.lhs = lhs;
      this.id = id;
    }

    private static final String[] PREFIXES = {"get", "is"};
    private static final boolean[] CHANGE_CASE = {false, true};

    @Override Object evaluate(EvaluationContext context) {
      Object lhsValue = lhs.evaluate(context);
      if (lhsValue == null) {
        throw new EvaluationException("Cannot get member " + id + " of null value");
      }
      // Velocity specifies that, given a reference .foo, it will first look for getfoo() and then
      // for getFoo(), and likewise given .Foo it will look for getFoo() and then getfoo().
      for (String prefix : PREFIXES) {
        for (boolean changeCase : CHANGE_CASE) {
          String baseId = changeCase ? changeInitialCase(id) : id;
          String methodName = prefix + baseId;
          Method method;
          try {
            method = lhsValue.getClass().getMethod(methodName);
            if (!prefix.equals("is") || method.getReturnType().equals(boolean.class)) {
              // Don't consider methods that happen to be called isFoo() but don't return boolean.
              try {
                return method.invoke(lhsValue);
              } catch (InvocationTargetException e) {
                throw evaluationException(e.getCause());
              } catch (Exception e) {
                throw evaluationException(e);
              }
            }
          } catch (NoSuchMethodException e) {
            // Continue with next possibility
          }
        }
      }
      throw new EvaluationException(
          "Member " + id + " does not correspond to a public getter of " + lhsValue
              + ", a " + lhsValue.getClass().getName());
    }

    private static String changeInitialCase(String id) {
      int initial = id.codePointAt(0);
      String rest = id.substring(Character.charCount(initial));
      if (Character.isUpperCase(initial)) {
        initial = Character.toLowerCase(initial);
      } else if (Character.isLowerCase(initial)) {
        initial = Character.toUpperCase(initial);
      }
      return new StringBuilder().appendCodePoint(initial).append(rest).toString();
    }
  }

  /**
   * A node in the parse tree that is an indexing of a reference, like {@code $x[0]} or
   * {@code $x.foo[$i]}. Indexing is array indexing or calling the {@code get} method of a list
   * or a map.
   */
  static class IndexReferenceNode extends ReferenceNode {
    final ReferenceNode lhs;
    final ExpressionNode index;

    IndexReferenceNode(ReferenceNode lhs, ExpressionNode index) {
      super(lhs.lineNumber);
      this.lhs = lhs;
      this.index = index;
    }

    @Override Object evaluate(EvaluationContext context) {
      Object lhsValue = lhs.evaluate(context);
      if (lhsValue == null) {
        throw new EvaluationException("Cannot index null value");
      }
      Object indexValue = index.evaluate(context);
      if (lhsValue instanceof List<?>) {
        if (!(indexValue instanceof Integer)) {
          throw new EvaluationException("List index is not an integer: " + indexValue);
        }
        List<?> lhsList = (List<?>) lhsValue;
        int i = (Integer) indexValue;
        if (i < 0 || i >= lhsList.size()) {
          throw new EvaluationException(
              "List index " + i + " is not valid for list of size " + lhsList.size());
        }
        return lhsList.get(i);
      } else if (lhsValue instanceof Map<?, ?>) {
        Map<?, ?> lhsMap = (Map<?, ?>) lhsValue;
        return lhsMap.get(indexValue);
      } else {
        throw new EvaluationException(
            "Cannot index an object of type " + lhsValue.getClass().getName());
      }
    }
  }
}
