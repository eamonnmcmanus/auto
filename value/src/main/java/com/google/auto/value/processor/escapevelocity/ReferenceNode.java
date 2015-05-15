package com.google.auto.value.processor.escapevelocity;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
abstract class ReferenceNode extends ExpressionNode {
  ReferenceNode(int lineNumber) {
    super(lineNumber);
  }

  static class PlainReferenceNode extends ReferenceNode {
    final String id;

    PlainReferenceNode(int lineNumber, String id) {
      super(lineNumber);
      this.id = id;
    }

    @Override Object evaluate(EvaluationContext context) {
      if (context.varIsDefined(id)) {
        Object value = context.getVar(id);
        if (value instanceof Node) {
          System.out.println(value);
          if (value instanceof ReferenceNode.PlainReferenceNode) {
            System.out.println(((ReferenceNode.PlainReferenceNode) value).id);
          }
          // This is a macro argument. Since those are call-by-name, we defer their evaluation until
          // they are actually used, which is here.
          Node thunk = (Node) value;
          return thunk.evaluate(context);
        } else {
          return value;
        }
      } else {
        throw new EvaluationException("Undefined reference $" + id);
      }
    }
  }

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
      for (String prefix : PREFIXES) {
        for (boolean changeCase : CHANGE_CASE) {
          String baseId = changeCase ? changeInitialCase(id) : id;
          String methodName = prefix + baseId;
          Method method;
          try {
            method = lhsValue.getClass().getMethod(methodName);
            if (prefix.equals("get") || method.getReturnType().equals(boolean.class)) {
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
