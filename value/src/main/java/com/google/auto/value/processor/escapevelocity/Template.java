package com.google.auto.value.processor.escapevelocity;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
public class Template {
  private final Node root;

  public static Template from(Reader reader) throws IOException {
    return new Parser(reader).parse();
  }

  Template(Node root) {
    this.root = root;
  }

  public String evaluate(Map<String, Object> vars) {
    EvaluationContext evaluationContext = new EvaluationContext(vars);
    return String.valueOf(root.evaluate(evaluationContext));
  }
}
