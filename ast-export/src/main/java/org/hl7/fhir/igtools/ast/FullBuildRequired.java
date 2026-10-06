package org.hl7.fhir.igtools.ast;

/**
 * Thrown when the incremental path finds something it cannot account for.
 * The caller runs a full build and reports {@link #getMessage()} as the
 * reason. "Cannot tell" is never incremental.
 */
public class FullBuildRequired extends Exception {

  private static final long serialVersionUID = 1L;

  public FullBuildRequired(String reason) {
    super(reason);
  }
}
