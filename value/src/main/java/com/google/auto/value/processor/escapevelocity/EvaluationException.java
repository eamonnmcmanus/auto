package com.google.auto.value.processor.escapevelocity;

/**
 * An exception that occurred while evaluating a template, such as an undefined variable reference
 * or a division by zero.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
public class EvaluationException extends RuntimeException {
  EvaluationException(String message) {
    super(message);
  }

  EvaluationException(Throwable cause) {
    super(cause);
  }
}
