package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Queues;

import java.util.Deque;
import java.util.List;

/**
 * A macro definition. Macros appear in templates using the syntax {@code #macro (m $x $y) ... #end}
 * and each one produces an instance of this class. Evaluating a macro involves setting the
 * parameters (here {$x $y)} and evaluating the macro body.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class Macro {
  private final int definitionLineNumber;
  private final String name;
  private final ImmutableList<String> parameterNames;
  private final Node body;

  Macro(int definitionLineNumber, String name, List<String> parameterNames, Node body) {
    this.definitionLineNumber = definitionLineNumber;
    this.name = name;
    this.parameterNames = ImmutableList.copyOf(parameterNames);
    this.body = body;
  }

  String name() {
    return name;
  }

  Object evaluate(EvaluationContext context, List<Object> arguments) {
    try {
      if (arguments.size() != parameterNames.size()) {
        throw new IllegalArgumentException(
            "Wrong number of arguments: expected " + parameterNames.size()
                + ", got " + arguments.size());
      }
      Deque<Runnable> undoes = Queues.newArrayDeque();
      for (int i = 0; i < parameterNames.size(); i++) {
        undoes.add(context.setVar(parameterNames.get(i), arguments.get(i)));
      }
      Object result = body.evaluate(context);
      while (!undoes.isEmpty()) {
        undoes.removeLast().run();
      }
      return result;
    } catch (EvaluationException e) {
      EvaluationException newException = new EvaluationException(
          "In macro #" + name + " defined on line " + definitionLineNumber + ": " + e.getMessage());
      newException.setStackTrace(e.getStackTrace());
      throw e;
    }
  }
}
