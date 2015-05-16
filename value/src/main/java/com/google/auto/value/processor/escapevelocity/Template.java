package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

/**
 * A template expressed in EscapeVelocity, a subset of the Velocity Template Language (VTL) from
 * Apache.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
public class Template {
  private final Node root;
  private final ImmutableMap<String, Macro> macros;

  /**
   * Parse a VTL template from the given {@code Reader}.
   */
  public static Template from(Reader reader) throws IOException {
    return new Parser(reader).parse();
  }

  Template(Node root, Map<String, Macro> macros) {
    this.root = root;
    this.macros = ImmutableMap.copyOf(macros);
  }

  /**
   * Evaluate the given template with the given initial set of variables.
   *
   * @param vars a map where the keys are variable names and the values are the corresponding
   *     variable values. For example, if {@code "x"} maps to 23, then {@code $x} in the template
   *     will expand to 23.
   *
   * @return the string result of evaluating the template.
   */
  public String evaluate(Map<String, Object> vars) {
    EvaluationContext evaluationContext =
        new EvaluationContext.PlainEvaluationContext(vars, macros);
    return String.valueOf(root.evaluate(evaluationContext));
  }
}
