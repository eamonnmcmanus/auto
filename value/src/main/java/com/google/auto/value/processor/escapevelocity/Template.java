package com.google.auto.value.processor.escapevelocity;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
public class Template {
  private final Node root;
  private final ImmutableMap<String, Macro> macros;

  public static Template from(Reader reader) throws IOException {
    return new Parser(reader).parse();
  }

  Template(Node root, Map<String, Macro> macros) {
    this.root = root;
    this.macros = ImmutableMap.copyOf(macros);
  }

  public String evaluate(Map<String, Object> vars) {
    EvaluationContext evaluationContext =
        new EvaluationContext.PlainEvaluationContext(vars, macros);
    return String.valueOf(root.evaluate(evaluationContext));
  }
}
