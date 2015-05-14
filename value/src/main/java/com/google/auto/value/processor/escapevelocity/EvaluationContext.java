package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.TreeMap;

/**
 * The context of a template evaluation. This consists of the template variables and the template
 * macros. The template variables start with the values supplied by the evaluation call, and can
 * be changed by {@code #set} directives and during the execution of {@code #foreach} and macro
 * calls. The macros are extracted from the template during parsing and never change thereafter.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class EvaluationContext {
  private final Map<String, Object> vars;
  private final ImmutableMap<String, Macro> macros;

  EvaluationContext(Map<String, Object> vars, ImmutableMap<String, Macro> macros) {
    this.vars = new TreeMap<String, Object>(vars);
    this.macros = macros;
  }

  Object getVar(String var) {
    return vars.get(var);
  }

  boolean varIsDefined(String var) {
    return vars.containsKey(var);
  }

  /**
   * Set the given variable to the given value.
   *
   * @return a Runnable that will restore the variable to the value it had before. If the variable
   * was undefined before this method was executed, the Runnable will make it undefined again.
   */
  Runnable setVar(final String var, Object value) {
    Runnable undo;
    if (vars.containsKey(var)) {
      final Object oldValue = vars.get(var);
      undo = new Runnable() {
        @Override public void run() {
          vars.put(var, oldValue);
        }
      };
    } else {
      undo = new Runnable() {
        @Override public void run() {
          vars.remove(var);
        }
      };
    }
    vars.put(var, value);
    return undo;
  }

  Macro getMacro(String name) {
    return macros.get(name);
  }
}
