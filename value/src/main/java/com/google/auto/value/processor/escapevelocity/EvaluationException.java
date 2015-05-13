package com.google.auto.value.processor.escapevelocity;

/**
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
