package com.google.auto.value.processor.escapevelocity;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
public class ParseException extends RuntimeException {
  ParseException(String message, int lineNumber, String context) {
    super(message + ", on line " + lineNumber + ", at text starting: " + context);
  }
}
