package com.google.auto.value.processor.escapevelocity;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.primitives.Primitives;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node in the parse tree representing a method reference, like {@code $list.size()}.
 *
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

  /**
   * {@inheritDoc}
   *
   * <p>Evaluating a method expression such as {@code $x.foo($y)} involves looking at the actual
   * types of {@code $x} and {@code $y}. The type of {@code $x} must have a public method
   * {@code foo} with a parameter type that is compatible with {@code $y}.
   *
   * <p>Currently we don't allow there to be more than one matching method. That is a difference
   * from Velocity, which blithely allows you to invoke {@link List#remove(int)} even though it
   * can't really know that you didn't mean to invoke {@link List#remove(Object)} with an Object
   * that just happens to be an Integer.
   * 
   * <p>The method to be invoked must be visible in a public class or interface that is either the
   * class of {@code $x} itself or one of its supertypes. Allowing supertypes is important because
   * you want to invoke a public method like {@link List#size()} on a list whose class is not
   * public, such as the list returned by {@link Collections#singletonList}.
   */
  @Override Object evaluate(EvaluationContext context) {
    Object lhsValue = lhs.evaluate(context);
    if (lhsValue == null) {
      throw new EvaluationException("Cannot invoke method " + id + " on null value");
    }
    List<Object> argValues = new ArrayList<Object>();
    for (ExpressionNode arg : args) {
      argValues.add(arg.evaluate(context));
    }
    List<Method> methodsWithName = Lists.newArrayList();
    for (Method method : lhsValue.getClass().getMethods()) {
      if (method.getName().equals(id)) {
        methodsWithName.add(method);
      }
    }
    if (methodsWithName.isEmpty()) {
      throw new EvaluationException("No method " + id + " in " + lhsValue.getClass().getName());
    }
    List<Method> compatibleMethods = Lists.newArrayList();
    for (Method method : methodsWithName) {
      // TODO(emcmanus): support varargs, if it's useful
      if (compatibleArgs(method.getParameterTypes(), argValues)) {
        compatibleMethods.add(method);
      }
    }
    switch (compatibleMethods.size()) {
      case 0:
        throw new EvaluationException(
            "Wrong type parameters for method " + id + " on line " + lineNumber + ": " + argValues);
      case 1:
        return invokeMethod(Iterables.getOnlyElement(compatibleMethods), lhsValue, argValues);
      default:
        throw new EvaluationException(
            "Ambiguous method invocation on line " + lineNumber + ", could be one of:"
            + Joiner.on('\n').join(compatibleMethods));
    }
  }

  private static Object invokeMethod(Method method, Object target, List<Object> argValues) {
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

  /**
   * Returns a Method with the same name and parameter types as the given one, but that is in a
   * public class or interface. This might be the given method, or it might be a method in a
   * superclass or superinterface.
   *
   * @return a public method in a public class or interface, or null if none was found.
   */
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

  /**
   * Determines if the given argument list is compatible with the given parameter types. This
   * includes an {@code Integer} argument being compatible with a parameter of type {@code int} or
   * {@code long}, for example.
   */
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

  /**
   * Returns true if {@code from} can be assigned to {@code to} according to
   * <a href="https://docs.oracle.com/javase/specs/jls/se8/html/jls-5.html#jls-5.1.2">Widening
   * Primitive Conversion</a>.
   */
  static boolean primitiveTypeIsAssignmentCompatible(Class<?> to, Class<?> from) {
    // To restate the JLS rules, f can be assigned to t if:
    // - they are the same; or
    // - f is char and t is a numeric type at least as wide as int; or
    // - f comes before t in the order byte, short, int, long, float, double.
    if (to == from) {
      return true;
    }
    int toI = NUMERICAL_PRIMITIVES.indexOf(to);
    if (toI < 0) {
      return false;
    }
    if (from == char.class) {
      return toI >= NUMERICAL_PRIMITIVES.indexOf(int.class);
    }
    int fromI = NUMERICAL_PRIMITIVES.indexOf(from);
    if (fromI < 0) {
      return false;
    }
    return toI >= fromI;
  }
}
