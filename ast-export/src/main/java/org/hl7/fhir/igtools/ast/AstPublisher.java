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
import org.hl7.fhir.model.utilities.formats.OutputStyle;
import org.hl7.fhir.model.core.CanonicalResource;
import org.hl7.fhir.model.core.Resource;
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

  /**
   * The inputs as they stood BEFORE the build, or null when the caller did
   * not record them. Must be taken before {@link #execute()}: SUSHI and the
   * Publisher write files under {@code input/} that git does not ignore, and
   * a digest taken after the build hashes them, so no clean clone of the
   * same commit can ever reproduce it (bean mac1, 2026-10-02: smart-trust
   * recorded {@code 7c2f6d91…} where a fresh clone computes
   * {@code c1023d82…}).
   */
  private JsonObject inputsBeforeBuild;

  /** How {@link #inputsBeforeBuild}'s digest chose its files, recorded at the same moment. */
  private JsonObject digestModeBeforeBuild;

  public AstPublisher() {
    super();
  }

  /** Records the inputs this build is valid for. Call before {@link #execute()}. */
  public void recordInputs(String root) throws IOException {
    inputsBeforeBuild = inputs(root, null);
    digestModeBeforeBuild = digestMode(root);
    notAtRevisionBeforeBuild = notAtRevision(InputDigest.notAtRevision(Path.of(root)));
    if (notAtRevisionBeforeBuild != null) {
      System.err.println("WARNING: " + notAtRevisionBeforeBuild.asInteger("count") + " input file(s) differ from "
          + "the committed revision, e.g. " + notAtRevisionBeforeBuild.getJsonArray("sample").get(0).asString()
          + ". This AST will not verify as a base for an incremental build. Build from a clean tree.");
    }
  }

  /** What {@link #recordInputs} found not at the revision, or null when the tree was clean. */
  private JsonObject notAtRevisionBeforeBuild;

  /** {@code {count, sample}} for a non-empty list, else null. */
  static JsonObject notAtRevision(java.util.List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return null;
    }
    org.hl7.fhir.utilities.json.model.JsonArray sample = new org.hl7.fhir.utilities.json.model.JsonArray();
    paths.stream().limit(5).forEach(sample::add);
    return new JsonObject().add("count", paths.size()).add("sample", sample);
  }

  JsonObject recordedInputs() {
    return inputsBeforeBuild;
  }

  /** The cache package of an incremental round, or null in an ordinary build. */
  private String cachePackage;
  /** Its {@code package/} folder, and canonical → file name from its index. */
  private Path cacheDir;
  private final java.util.Map<String, String> cacheFiles = new java.util.HashMap<>();

  /**
   * Names the cache package whose resources stand in for the IG's own
   * (IncrementalBuildCli), and the folder it was written to.
   */
  public void setCachePackage(String name, Path packageFolder) throws IOException {
    this.cachePackage = name;
    this.cacheDir = packageFolder.resolve("package");
    org.hl7.fhir.utilities.json.model.JsonObject idx = org.hl7.fhir.utilities.json.parser.JsonParser.parseObject(
        java.nio.file.Files.readString(cacheDir.resolve(".index.json")));
    for (org.hl7.fhir.utilities.json.model.JsonElement je : idx.getJsonArray("files")) {
      org.hl7.fhir.utilities.json.model.JsonObject f = je.asJsonObject();
      if (f.has("url")) {
        cacheFiles.put(f.asString("url"), f.asString("filename"));
      }
    }
  }

  /**
   * A cached resource resolves WITH its content, as the IG's own resources do
   * in a full build. The Publisher finds a dependency package's resource
   * through the package's spec map, which gives the link but loads content only
   * for examples, so the renderer had no name to show and printed the canonical
   * (bean w7r4: the library links in 41 Measure narratives).
   */
  @Override
  public org.hl7.fhir.services.renderers.utils.Resolver.ResourceWithReference resolve(
      org.hl7.fhir.services.renderers.utils.RenderingContext context, String url, String version) throws IOException {
    org.hl7.fhir.services.renderers.utils.Resolver.ResourceWithReference r = super.resolve(context, url, version);
    if (r == null || r.getResource() != null || cacheDir == null || url == null) {
      return r;
    }
    String file = cacheFiles.get(url.contains("|") ? url.substring(0, url.indexOf('|')) : url);
    if (file == null) {
      return r;
    }
    try (java.io.InputStream in = java.nio.file.Files.newInputStream(cacheDir.resolve(file))) {
      org.hl7.fhir.services.elementmodel.Element e =
          new org.hl7.fhir.services.elementmodel.JsonParser(new AstFieldsAccess(this).context()).parseSingle(in, null);
      return new org.hl7.fhir.services.renderers.utils.Resolver.ResourceWithReference(r.getKind(), r.getUrlReference(),
          r.getWebPath(), org.hl7.fhir.services.renderers.utils.ResourceWrapper.forResource(context, e));
    } catch (Exception ex) {
      return r;
    }
  }

  /**
   * After the dependencies are loaded and before anything is rendered, the
   * cached resources link as the IG's own pages do. The Publisher gives a
   * package resource the path {@code <package web location>/<page>}; the cache
   * package's location is empty, so that is {@code /ValueSet-X.html}, a link to
   * the server root, where a full build writes {@code ValueSet-X.html} (bean
   * w7r4: 41 Measure and 2 StructureMap narratives).
   */
  @Override
  public void checkDependencies(org.hl7.fhir.igtools.publisher.FetchedFile igf, org.hl7.fhir.utilities.IniFile buildTracker)
      throws Exception {
    super.checkDependencies(igf, buildTracker);
    if (cachePackage != null) {
      // One fetch per cached canonical: the context refuses to list some types
      // (fetchResourcesByType(ValueSet) throws "not allow Iterating ValueSet
      // resources", bean w7r5). A rendering detail never fails the build.
      try {
        org.hl7.fhir.services.context.IWorkerContext ctx = new AstFieldsAccess(this).context();
        List<Resource> cached = new ArrayList<>();
        for (String url : cacheFiles.keySet()) {
          try {
            Resource r = ctx.fetchResource(Resource.class, url);
            if (r != null) {
              cached.add(r);
            }
          } catch (Throwable e) {
            // not in the context, or the context refuses (it throws Error): skip
          }
        }
        int n = relativiseCacheLinks(cached, cachePackage);
        System.out.println("ast-export: " + n + " cached resource link(s) made relative");
      } catch (Throwable t) {
        System.out.println("ast-export: WARNING: cached links not made relative: " + t);
      }
    }
  }

  /** Drops the leading "/" from the web path of each resource from {@code pkg}; returns how many. */
  static int relativiseCacheLinks(List<? extends Resource> resources, String pkg) {
    int n = 0;
    for (Resource r : resources) {
      if (r.hasSourcePackage() && pkg.equals(r.getSourcePackage().getId()) && r.hasWebPath()
          && r.getWebPath().startsWith("/")) {
        r.setWebPath(r.getWebPath().substring(1));
        n++;
      }
    }
    return n;
  }

  /** Writes the AST for the run that just completed. */
  public void exportAst(Path outDir) throws IOException {
    AstFieldsAccess fields = new AstFieldsAccess(this);
    String root = fields.rootDir();
    AstExporter exporter = new AstExporter(r -> composeJson(fields, r), Path.of(root));
    JsonObject h = header(fields, root, inputsBeforeBuild, digestModeBeforeBuild);
    // Beside inputs, never inside it (inputs is exactly CompiledInputsSchema).
    if (notAtRevisionBeforeBuild != null) {
      h.add("inputsNotAtRevision", notAtRevisionBeforeBuild);
    }
    addProvenance(h, fields, cachePackage);
    exporter.export(getFileList(), outDir, h, upstreamEdges(fields));
    // SUSHI's own map from .fsh file to output resource, kept with the AST so
    // a later delta can be mapped even for a file that has since been deleted.
    Path fshIndex = Path.of(root, "fsh-generated", "data", "fsh-index.json");
    if (java.nio.file.Files.exists(fshIndex)) {
      java.nio.file.Files.copy(fshIndex, outDir.resolve("fsh-index.json"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Criteria 4 and 5 (W3): the pinned package closure and the terminology
   * provenance. A failure here is reported in the manifest and never fails
   * the export.
   */
  static void addProvenance(JsonObject h, AstFieldsAccess fields, String excludePackage) {
    try {
      List<Provenance.Pkg> pkgs = new ArrayList<>();
      for (org.hl7.fhir.utilities.npm.NpmPackage p : fields.loadedPackages()) {
        pkgs.add(new Provenance.Pkg(p.name(), p.version(), p.canonical(), p.fhirVersion(), p.dependencies()));
      }
      h.add("packages", Provenance.packages(pkgs, excludePackage));
    } catch (Throwable t) {
      h.add("packagesUnknown", String.valueOf(t));
    }
    try {
      String address = null;
      String version = null;
      try {
        org.hl7.fhir.services.client.ITerminologyClientN c = fields.context().getTerminologyClientManager().getMasterClient();
        if (c != null) {
          address = c.getAddress();
          version = c.getServerVersion();
        }
      } catch (Throwable t) {
        // no server (offline build): the server object stays empty
      }
      List<Provenance.Expansion> exps = new ArrayList<>();
      for (org.hl7.fhir.model.core.ValueSet vs : fields.expansions()) {
        Map<String, List<String>> params = new java.util.TreeMap<>();
        if (vs.hasExpansion()) {
          for (org.hl7.fhir.model.core.ValueSet.ValueSetExpansionParameterComponent p : vs.getExpansion().getParameterList()) {
            if (p.hasValue() && p.getValue().primitiveValue() != null) {
              params.computeIfAbsent(p.getName(), k -> new ArrayList<>()).add(p.getValue().primitiveValue());
            }
          }
        }
        exps.add(new Provenance.Expansion(vs.getUrl(), vs.getVersion(), params));
      }
      h.add("terminology", Provenance.terminology(address, version, exps));
    } catch (Throwable t) {
      h.add("terminologyUnknown", String.valueOf(t));
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
    org.hl7.fhir.services.elementmodel.Element e = r.getElement();
    return AstExporter.keyOf(r.fhirType(), r.getId(), AstExporter.childValue(e, "url"),
        AstExporter.childValue(e, "version"));
  }

  static JsonObject header(AstFieldsAccess fields, String root, JsonObject recorded) throws IOException {
    return header(fields, root, recorded, null);
  }

  static JsonObject header(AstFieldsAccess fields, String root, JsonObject recorded, JsonObject recordedMode)
      throws IOException {
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
    // Recorded before the build when the caller did so; computing it here,
    // after the build, is the fallback that cannot verify on a clean clone.
    JsonObject inputs = recorded != null ? recorded : inputs(root, toolchain);
    h.add("inputs", inputs);
    if (!inputs.has("sourceRevision")) {
      h.add("inputsUnknown", new JsonObject().add("sourceRevision", "the IG root is not a git checkout"));
    }
    // Beside inputs, never inside it: inputs is exactly CompiledInputsSchema.
    // Taken with the digest it describes: before the build when the inputs
    // were, so the mode never labels a different file set from the digest.
    h.add("inputDigestMode", recorded != null && recordedMode != null ? recordedMode : digestMode(root));
    return h;
  }

  /** How the input digest chose its files (git or walk), and why. */
  static JsonObject digestMode(String root) throws IOException {
    InputDigest.Result d = InputDigest.compute(Path.of(root));
    JsonObject how = new JsonObject().add("mode", d.mode());
    if (d.reason() != null) {
      how.add("reason", d.reason());
    }
    return how;
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
    new org.hl7.fhir.services.elementmodel.JsonParser(fields.context()).compose(r.getElement(), out, OutputStyle.PRETTY, null);
    return out.toByteArray();
  }
}
