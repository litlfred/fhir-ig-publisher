package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.igtools.publisher.AstFieldsAccess;
import org.hl7.fhir.igtools.publisher.DependencyAnalyser;
import org.hl7.fhir.igtools.publisher.FetchedFile;
import org.hl7.fhir.igtools.publisher.FetchedResource;
import org.hl7.fhir.igtools.publisher.Publisher;
import org.hl7.fhir.r5.formats.IParser.OutputStyle;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.utilities.json.model.JsonObject;

/**
 * A {@link Publisher} that, after a normal {@link #execute()}, can dump the
 * resources it built as an AST. It overrides nothing: the build is the
 * Publisher's own, and the export only reads what the build left in memory.
 *
 * <p>Taken AFTER the build, so resources carry the narratives the build
 * generated. A snapshot before rendering would need a hook upstream does
 * not expose.
 */
public class AstPublisher extends Publisher {

  public AstPublisher() {
    super();
  }

  /** Writes the AST for the run that just completed. */
  public void exportAst(Path outDir) throws IOException {
    AstFieldsAccess fields = new AstFieldsAccess(this);
    String root = fields.rootDir();
    AstExporter exporter = new AstExporter(r -> composeJson(fields, r), Path.of(root));
    exporter.export(getFileList(), outDir, header(fields, root), upstreamEdges(fields));
    // SUSHI's own map from .fsh file to output resource, kept with the AST so
    // a later delta can be mapped even for a file that has since been deleted.
    Path fshIndex = Path.of(root, "fsh-generated", "data", "fsh-index.json");
    if (java.nio.file.Files.exists(fshIndex)) {
      java.nio.file.Files.copy(fshIndex, outDir.resolve("fsh-index.json"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Upstream's own DependencyAnalyser, reused as it is — terminology and
   * conformance edges. It only sees resources with a parsed Resource, and
   * drops targets it cannot resolve; LogicEdges covers what it does not.
   */
  List<AstExporter.EdgeRow> upstreamEdges(AstFieldsAccess fields) {
    DependencyAnalyser analyser = new DependencyAnalyser(fields.context());
    Map<Resource, String> keys = new IdentityHashMap<>();
    for (FetchedFile f : getFileList()) {
      for (FetchedResource r : f.getResources()) {
        if (r.getResource() != null) {
          analyser.analyse(r.getResource());
          keys.put(r.getResource(), keyFor(r));
        }
      }
    }
    List<AstExporter.EdgeRow> rows = new ArrayList<>();
    for (DependencyAnalyser.ArtifactDependency d : analyser.getList()) {
      String src = keys.get(d.getSource());
      if (src == null || !(d.getTarget() instanceof CanonicalResource t)) {
        continue;
      }
      rows.add(new AstExporter.EdgeRow(src, d.getKind(), t.getUrl(), t.getVersion(), null, "publisher"));
    }
    return rows;
  }

  static String keyFor(FetchedResource r) {
    org.hl7.fhir.r5.elementmodel.Element e = r.getElement();
    return AstExporter.keyOf(r.fhirType(), r.getId(), AstExporter.childValue(e, "url"),
        AstExporter.childValue(e, "version"));
  }

  static JsonObject header(AstFieldsAccess fields, String root) throws IOException {
    JsonObject h = new JsonObject();
    h.add("generatedAt", Instant.now().toString());
    JsonObject ig = new JsonObject();
    ig.add("root", root);
    if (fields.igVersion() != null) {
      ig.add("version", fields.igVersion());
    }
    h.add("ig", ig);
    JsonObject toolchain = Toolchain.describe(root);
    h.add("toolchain", toolchain);
    JsonObject inputs = inputs(root, toolchain);
    h.add("inputs", inputs);
    if (!inputs.has("sourceRevision")) {
      h.add("inputsUnknown", new JsonObject().add("sourceRevision", "the IG root is not a git checkout"));
    }
    return h;
  }

  /**
   * The inputs this build is valid for — EXACTLY folio-assistant's
   * {@code CompiledInputsSchema} ({@code folio-assistant-core/schemas/materialization.ts}),
   * which is strict: {@code toolchain}, {@code sourceRevision}, and
   * {@code inputDigest} as 64 lowercase hex characters, nothing else. A
   * consumer runs its {@code compiledValidity} on this object unchanged.
   * {@code sourceRevision} is OMITTED when unknown (the schema requires a
   * non-empty string), and why is written beside the object by the caller.
   */
  static JsonObject inputs(String root, JsonObject toolchain) throws IOException {
    if (toolchain == null) {
      toolchain = Toolchain.describe(root);
    }
    JsonObject inputs = new JsonObject();
    inputs.add("toolchain", "ig-publisher " + toolchain.asString("publisher") + " / core " + toolchain.asString("core"));
    String rev = Toolchain.sourceRevision(root);
    if (rev != null) {
      inputs.add("sourceRevision", rev);
    }
    inputs.add("inputDigest", InputDigest.of(Path.of(root)));
    return inputs;
  }

  static byte[] composeJson(AstFieldsAccess fields, FetchedResource r) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    new org.hl7.fhir.r5.elementmodel.JsonParser(fields.context()).compose(r.getElement(), out, OutputStyle.PRETTY, null);
    return out.toByteArray();
  }
}
