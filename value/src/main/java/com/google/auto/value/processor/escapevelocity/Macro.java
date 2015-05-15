package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;

import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A macro definition. Macros appear in templates using the syntax {@code #macro (m $x $y) ... #end}
 * and each one produces an instance of this class. Evaluating a macro involves setting the
 * parameters (here {$x $y)} and evaluating the macro body. Macro arguments are call-by-name, which
 * means that we need to set each parameter variable to the node in the parse tree that corresponds
 * to it, and arrange for that node to be evaluated when the variable is actually referenced.
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

  Object evaluate(EvaluationContext context, List<Node> thunks) {
    try {
      if (thunks.size() != parameterNames.size()) {
        throw new IllegalArgumentException(
            "Wrong number of arguments: expected " + parameterNames.size()
                + ", got " + thunks.size());
      }
      Map<String, Node> parameterThunks = Maps.newLinkedHashMap();
      for (int i = 0; i < parameterNames.size(); i++) {
        parameterThunks.put(parameterNames.get(i), thunks.get(i));
      }
      EvaluationContext newContext = new MacroEvaluationContext(parameterThunks, context);
      return body.evaluate(newContext);
    } catch (EvaluationException e) {
      EvaluationException newException = new EvaluationException(
          "In macro #" + name + " defined on line " + definitionLineNumber + ": " + e.getMessage());
      newException.setStackTrace(e.getStackTrace());
      throw e;
    }
  }

  static class MacroEvaluationContext implements EvaluationContext {
    private final Map<String, Node> parameterThunks;
    private final EvaluationContext originalEvaluationContext;

    MacroEvaluationContext(
        Map<String, Node> parameterThunks, EvaluationContext originalEvaluationContext) {
      this.parameterThunks = parameterThunks;
      this.originalEvaluationContext = originalEvaluationContext;
    }

    @Override
    public Object getVar(String var) {
      Node thunk = parameterThunks.get(var);
      if (thunk == null) {
        return originalEvaluationContext.getVar(var);
      } else {
        return thunk.evaluate(originalEvaluationContext);
      }
    }

    @Override
    public boolean varIsDefined(String var) {
      return parameterThunks.containsKey(var) || originalEvaluationContext.varIsDefined(var);
    }

    @Override
    public Runnable setVar(final String var, Object value) {
      // Copy the behaviour that #set will shadow a macro parameter, even though the Velocity peeps
      // seem to agree that that is not good.
      final Node thunk = parameterThunks.get(var);
      if (thunk == null) {
        return originalEvaluationContext.setVar(var, value);
      } else {
        parameterThunks.remove(var);
        final Runnable originalUndo = originalEvaluationContext.setVar(var, value);
        return new Runnable() {
          @Override
          public void run() {
            originalUndo.run();
            parameterThunks.put(var, thunk);
          }
        };
      }
    }

    @Override
    public Macro getMacro(String name) {
      return originalEvaluationContext.getMacro(name);
    }
  }
}
