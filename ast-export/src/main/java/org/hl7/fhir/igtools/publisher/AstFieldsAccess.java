package org.hl7.fhir.igtools.publisher;

import org.hl7.fhir.services.context.IWorkerContext;

/**
 * Read-only view of the few {@link PublisherFields} the AST export needs.
 *
 * <p>Those fields are package-private, so this class is declared in the
 * Publisher's own package — inside the ast-export library, not in
 * publisher.core. No existing class is modified and nothing is written
 * through it. If upstream renames a field this fails to COMPILE, which is
 * the point: a reflective lookup would fail at run time instead.
 */
public final class AstFieldsAccess {

  private final PublisherFields pf;

  public AstFieldsAccess(Publisher publisher) {
    this.pf = publisher.getPf();
  }

  public String rootDir() {
    return pf.rootDir;
  }

  public String outputDir() {
    return pf.outputDir;
  }

  /** The IG's own version (sushi-config / ImplementationGuide.version). */
  public String igVersion() {
    return pf.version;
  }

  public IWorkerContext context() {
    return pf.context;
  }

  /** Every package the build loaded, the IG's dependencies and theirs (W3). */
  public java.util.List<org.hl7.fhir.utilities.npm.NpmPackage> loadedPackages() {
    return java.util.Collections.unmodifiableList(pf.npmList);
  }

  /** The expansions the build made, as it saves them to expansions.json (W3). */
  public java.util.List<org.hl7.fhir.model.core.ValueSet> expansions() {
    return java.util.Collections.unmodifiableList(pf.expansions);
  }
}
