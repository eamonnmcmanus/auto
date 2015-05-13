package com.google.auto.value.processor.escapevelocity;

import static com.google.common.truth.Truth.assert_;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Primitives;
import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ReferenceNode}.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
@RunWith(JUnit4.class)
public class ReferenceNodeTest {
  @Rule public Expect expect = Expect.create();

  private static ImmutableList<Class<?>> pair(Class<?> a, Class<?> b) {
    return ImmutableList.of(a, b);
  }

  // This is the exhaustive list from
  // https://docs.oracle.com/javase/specs/jls/se8/html/jls-5.html#jls-5.1.2.
  // We put the "from" type first for consistency with that list, even though that is inconsistent
  // with our method order (which is itself consistent with assignment, "to" on the left).
  private static final ImmutableSet<ImmutableList<Class<?>>> ASSIGNMENT_COMPATIBLE =
      ImmutableSet.of(
          pair(byte.class, short.class),
          pair(byte.class, int.class),
          pair(byte.class, long.class),
          pair(byte.class, float.class),
          pair(byte.class, double.class),
          pair(short.class, int.class),
          pair(short.class, long.class),
          pair(short.class, float.class),
          pair(short.class, double.class),
          pair(char.class, int.class),
          pair(char.class, long.class),
          pair(char.class, float.class),
          pair(char.class, double.class),
          pair(int.class, long.class),
          pair(int.class, float.class),
          pair(int.class, double.class),
          pair(long.class, float.class),
          pair(long.class, double.class),
          pair(float.class, double.class));

  @Test
  public void testPrimitiveTypeIsAssignmentCompatible() {
    for (Class<?> from : Primitives.allPrimitiveTypes()) {
      for (Class<?> to : Primitives.allPrimitiveTypes()) {
        boolean expected =
            (from == to || ASSIGNMENT_COMPATIBLE.contains(ImmutableList.of(from, to)));
        boolean actual =
            ReferenceNode.MethodReferenceNode.primitiveTypeIsAssignmentCompatible(to, from);
        expect
            .withFailureMessage(from + " assignable to " + to)
            .that(expected).isEqualTo(actual);
      }
    }
  }
}
