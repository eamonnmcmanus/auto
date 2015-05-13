package com.google.auto.value.processor.escapevelocity;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Primitives;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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

    @Override public Object evaluate(EvaluationContext context) {
      if (context.varIsDefined(id)) {
        return context.getVar(id);
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

    @Override public Object evaluate(EvaluationContext context) {
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
                throw new EvaluationException(e.getCause());
              } catch (Exception e) {
                throw new EvaluationException(e.getCause());
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

  static class MethodReferenceNode extends ReferenceNode {
    final ReferenceNode lhs;
    final String id;
    final List<ExpressionNode> args;

    MethodReferenceNode(ReferenceNode lhs, String id, List<ExpressionNode> args) {
      super(lhs.lineNumber);
      this.lhs = lhs;
      this.id = id;
      this.args = args;
    }

    @Override public Object evaluate(EvaluationContext context) {
      Object lhsValue = lhs.evaluate(context);
      if (lhsValue == null) {
        throw new EvaluationException("Cannot invoke method " + id + " on null value");
      }
      List<Object> argValues = new ArrayList<Object>();
      for (ExpressionNode arg : args) {
        argValues.add(arg.evaluate(context));
      }
      boolean foundAny = false;
      for (Method method : lhsValue.getClass().getMethods()) {
        if (method.getName().equals(id)) {
          foundAny = true;
          // TODO(emcmanus): support varargs, if it's useful
          if (compatibleArgs(method.getParameterTypes(), argValues)) {
            return invokeMethod(method, lhsValue, argValues);
          }
        }
      }
      if (foundAny) {
        throw new EvaluationException(
            "Wrong type parameters for method " + id + ": " + argValues);
      } else {
        throw new EvaluationException(
            "No method " + id + " in " + lhsValue.getClass().getName());
      }
    }

    static Object invokeMethod(Method method, Object target, List<Object> argValues) {
      if (!Modifier.isPublic(target.getClass().getModifiers())) {
        method = visibleMethod(method, target.getClass());
        if (method == null) {
          throw new EvaluationException(
              "Method is not visible in class " + target.getClass().getName() + ": " + method);
        }
      }
      try {
        return method.invoke(target, argValues.toArray());
      } catch (InvocationTargetException e) {
        throw new EvaluationException(e.getCause());
      } catch (Exception e) {
        throw new EvaluationException(e);
      }
    }

    private static Method visibleMethod(Method method, Class<?> in) {
      if (in == null) {
        return null;
      }
      try {
        in.getMethod(method.getName(), method.getParameterTypes());
      } catch (NoSuchMethodException e) {
        return null;
      }
      if (Modifier.isPublic(in.getModifiers())) {
        return method;
      }
      Method methodSuper = visibleMethod(method, in.getSuperclass());
      if (methodSuper != null) {
        return methodSuper;
      }
      for (Class<?> intf : in.getInterfaces()) {
        Method methodIntf = visibleMethod(method, intf);
        if (methodIntf != null) {
          return methodIntf;
        }
      }
      return null;
    }

    private static boolean compatibleArgs(Class<?>[] paramTypes, List<Object> argValues) {
      if (paramTypes.length != argValues.size()) {
        return false;
      }
      for (int i = 0; i < paramTypes.length; i++) {
        Class<?> paramType = paramTypes[i];
        Object argValue = argValues.get(i);
        if (paramType.isPrimitive()) {
          return primitiveIsCompatible(paramType, argValue);
        } else if (!paramType.isInstance(argValue)) {
          return false;
        }
      }
      return true;
    }

    private static final ImmutableList<Class<?>> NUMERICAL_PRIMITIVES = ImmutableList.<Class<?>>of(
        byte.class, short.class, int.class, long.class, float.class, double.class);

    private static boolean primitiveIsCompatible(Class<?> primitive, Object value) {
      if (value == null || !Primitives.isWrapperType(value.getClass())) {
        return false;
      }
      return primitiveTypeIsAssignmentCompatible(primitive, value.getClass());
    }

    static boolean primitiveTypeIsAssignmentCompatible(Class<?> to, Class<?> from) {
      if (to == from) {
        return true;
      }
      if (from == char.class && to != short.class) {
        from = short.class;
      }
      int toI = NUMERICAL_PRIMITIVES.indexOf(to);
      int fromI = NUMERICAL_PRIMITIVES.indexOf(from);
      if (fromI < 0) {
        return false;
      }
      return toI >= fromI;
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

    @Override public Object evaluate(EvaluationContext context) {
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
