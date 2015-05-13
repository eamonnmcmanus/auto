package com.google.auto.value.processor.escapevelocity;

import java.util.Map;
import java.util.TreeMap;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
class EvaluationContext {
  private final Map<String, Object> vars;
  private final Map<String, Macro> macros;

  EvaluationContext(Map<String, Object> vars) {
    this.vars = vars;
    this.macros = new TreeMap<String, Macro>();
  }

  Object getVar(String var) {
    return vars.get(var);
  }

  boolean varIsDefined(String var) {
    return vars.containsKey(var);
  }

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

  void defineMacro(Macro macro) {
    macros.put(macro.name(), macro);
  }

  Macro getMacro(String name) {
    return macros.get(name);
  }
}
