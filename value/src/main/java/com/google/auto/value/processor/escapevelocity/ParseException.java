package com.google.auto.value.processor.escapevelocity;

/**
 * An exception that occurred while parsing a template.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
public class ParseException extends RuntimeException {
  private static final long serialVersionUID = 1;

  ParseException(String message, int lineNumber) {
    super(message + ", on line " + lineNumber);
  }

  ParseException(String message, int lineNumber, String context) {
    super(message + ", on line " + lineNumber + ", at text starting: " + context);
  }
}
