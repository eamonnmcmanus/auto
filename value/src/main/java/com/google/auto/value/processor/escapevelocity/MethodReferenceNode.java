package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Primitives;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Éamonn McManus
 */
class MethodReferenceNode extends ReferenceNode {
  final ReferenceNode lhs;
  final String id;
  final List<ExpressionNode> args;

  MethodReferenceNode(ReferenceNode lhs, String id, List<ExpressionNode> args) {
    super(lhs.lineNumber);
    this.lhs = lhs;
    this.id = id;
    this.args = args;
  }

  @Override
  public Object evaluate(EvaluationContext context) {
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
      throw new EvaluationException("Wrong type parameters for method " + id + ": " + argValues);
    } else {
      throw new EvaluationException("No method " + id + " in " + lhsValue.getClass().getName());
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

  private static final String THIS_PACKAGE;
  static {
    String nodeClassName = Node.class.getName();
    int lastDot = nodeClassName.lastIndexOf('.');
    THIS_PACKAGE = nodeClassName.substring(0, lastDot + 1);
    // Package name plus trailing dot.
  }

  static Method visibleMethod(Method method, Class<?> in) {
    if (in == null) {
      return null;
    }
    Method methodInClass;
    try {
      methodInClass = in.getMethod(method.getName(), method.getParameterTypes());
    } catch (NoSuchMethodException e) {
      return null;
    }
    if (Modifier.isPublic(in.getModifiers()) || in.getName().startsWith(THIS_PACKAGE)) {
      // The second disjunct is a hack to allow us to use the methods of $foreach without having
      // to make the ForEachVar class public. We can invoke those methods from here since they
      // are in the same package.
      return methodInClass;
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

  static boolean compatibleArgs(Class<?>[] paramTypes, List<Object> argValues) {
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
    return primitiveTypeIsAssignmentCompatible(primitive, Primitives.unwrap(value.getClass()));
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
