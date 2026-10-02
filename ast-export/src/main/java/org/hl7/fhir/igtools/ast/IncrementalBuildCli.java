package org.hl7.fhir.igtools.ast;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.hl7.fhir.igtools.publisher.Publisher;
import org.hl7.fhir.igtools.publisher.PublisherUtils;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * {@code IncrementalBuildCli -ast <base> -ig <dir> -out <dir> [-head <rev> | -staged] [-work <dir>]
 * [-cache-folder <dir>] [-max-rounds 3] [-threshold 0.4] [-tx <url>]}
 *
 * <p>W7: import a base AST, plan the delta, rebuild only the cone, merge,
 * and repeat until the cone stops growing.
 *
 * <ol>
 *   <li>Plan ({@link IncrementalPlan}). A {@code full} decision runs one
 *       ordinary build instead and says why.</li>
 *   <li>Run SUSHI on the real IG, so {@code fsh-generated/} matches head.</li>
 *   <li>Write {@code loadFromCache} as a package into the package cache
 *       ({@link CachePackageWriter}).</li>
 *   <li>Assemble a temporary IG of only the rebuild set
 *       ({@link TempIgAssembler}) and build it with the stock Publisher.</li>
 *   <li>Merge ({@link AstMerger}). If the merged graph's cone reaches
 *       resources that were not rebuilt, rebuild those and merge again.</li>
 * </ol>
 *
 * <p><b>Guarded.</b> While {@link IncrementalPlan#INCREMENTAL_GUARD} is on,
 * every run takes the full-build branch; the plan is still written to
 * {@code <work>/plan.json} for review.
 *
 * <p><b>UNTESTED end to end</b> (2026-09-30): the package registry was
 * unreachable when it was written. Each piece is unit-tested alone.
 */
public class IncrementalBuildCli {

  public static void main(String[] args) throws Exception {
    String astArg = AstExportCli.param(args, "-ast");
    String igArg = AstExportCli.param(args, "-ig");
    String outArg = AstExportCli.param(args, "-out");
    if (astArg == null || igArg == null || outArg == null) {
      System.err.println("usage: IncrementalBuildCli -ast <base> -ig <dir> -out <dir> [-head <rev> | -staged] "
          + "[-work <dir>] [-cache-folder <dir>] [-max-rounds 3] [-threshold 0.4] [-tx <url>]");
      System.exit(2);
    }
    Path baseAst = Path.of(astArg);
    Path ig = Path.of(igArg).toAbsolutePath();
    Path out = Path.of(outArg);
    Path work = Path.of(orElse(AstExportCli.param(args, "-work"), ig.resolve("temp-ast-incremental").toString()));
    String cacheFolder = AstExportCli.param(args, "-cache-folder");
    int maxRounds = Integer.parseInt(orElse(AstExportCli.param(args, "-max-rounds"), "3"));
    double threshold = Double.parseDouble(orElse(AstExportCli.param(args, "-threshold"),
        String.valueOf(IncrementalPlan.DEFAULT_THRESHOLD)));
    String tx = AstExportCli.param(args, "-tx");

    JsonObject baseManifest = JsonParser.parseObject(Files.readString(baseAst.resolve("manifest.json")));
    String baseRev = baseManifest.getJsonObject("inputs").asString("sourceRevision");
    boolean staged = AstExportCli.has(args, "-staged");
    String head = staged ? null : orElse(AstExportCli.param(args, "-head"), "HEAD");
    List<AstDelta.Change> delta = AstDelta.fromGit(ig.toString(), baseRev, head);
    String fu = AstExportCli.param(args, "-fsh-users");
    JsonObject plan = new IncrementalPlan(baseAst, fu == null ? null : IncrementalPlan.readFshUsers(Path.of(fu)))
        .plan(delta, threshold);
    IncrementalPlan.guard(plan);
    Files.createDirectories(work);
    Files.writeString(work.resolve("plan.json"), JsonParser.compose(plan, true));

    if ("full".equals(plan.asString("decision"))) {
      System.out.println("Full build: " + JsonParser.compose(plan.getJsonArray("fullBuildBecause")));
      AstExportCli.main(new String[] {"-ig", ig.toString(), "-ast-out", out.toString()});
      return;
    }
    String headRev = staged ? "staged-on-" + Toolchain.sourceRevision(ig.toString())
        : Toolchain.run(ig.toString(), "git", "rev-parse", head);

    if (Toolchain.run(ig.toString(), "sushi", ".") == null) {
      throw new IllegalStateException("SUSHI failed in " + ig + "; the rebuild set's sources would be stale");
    }
    String igRel = TempIgAssembler.igResourcePath(ig.resolve("ig.ini"));
    JsonObject igRes = JsonParser.parseObject(Files.readString(ig.resolve(igRel)));
    String packageId = igRes.asString("packageId");
    String fhirVersion = igRes.getJsonArray("fhirVersion").get(0).asString();
    JsonObject headInputs = AstPublisher.inputs(ig.toString(), null);

    Set<String> rebuild = strings(plan, "rebuild");
    Set<String> remove = strings(plan, "remove");
    Set<String> newSources = new TreeSet<>();
    for (JsonElement je : plan.getJsonArray("files")) {
      JsonObject f = je.asJsonObject();
      if ("NEW_SOURCE".equals(f.asString("effect"))) {
        newSources.add(f.asString("path"));
      }
    }
    Path currentBase = baseAst;
    for (int round = 1; round <= maxRounds; round++) {
      IncrementalPlan current = new IncrementalPlan(currentBase, null);
      Set<String> load = current.toLoad(rebuild);
      String version = "0.0.0-ast." + shortRev(baseRev) + ".r" + round;
      Path cache = cacheFolder != null ? Path.of(cacheFolder)
          : Path.of(System.getProperty("user.home"), ".fhir", "packages");
      String name = CachePackageWriter.packageName(packageId);
      CachePackageWriter.write(currentBase, allExcept(currentBase, rebuild, remove), cache, name, version, fhirVersion);

      Map<String, String> sources = sources(currentBase);
      Set<String> libraries = libraryNames(currentBase, rebuild);
      Path roundDir = work.resolve("r" + round);
      TempIgAssembler.Result assembled = TempIgAssembler.assemble(ig, roundDir, rebuild, sources, libraries,
          name + "#" + version);
      for (String src : newSources) {
        copyNewSourceOutputs(ig, roundDir, src);
      }
      System.out.println("Round " + round + ": rebuilding " + rebuild.size() + " (" + assembled.missing().size()
          + " without a source), loading " + load.size() + " from cache");

      AstPublisher p = new AstPublisher();
      p.setConfigFile(Publisher.getAbsoluteConfigFilePath(
          Publisher.determineActualIG(roundDir.toString(), PublisherUtils.IGBuildMode.MANUAL)));
      p.getSettings().setNoSushi(true);
      p.getSettings().setCacheOption(PublisherUtils.CacheOption.LEAVE);
      if (cacheFolder != null) {
        p.getSettings().setPackageCacheFolder(cacheFolder);
      }
      if (tx != null) {
        p.getSettings().setTxServer(tx);
      }
      p.execute();
      Path partial = roundDir.resolve("output-ast");
      p.exportAst(partial);

      Path merged = work.resolve("merged-r" + round);
      AstMerger.Result r = AstMerger.merge(currentBase, partial, rebuild, remove, baseRev, headRev, headInputs,
          merged);
      currentBase = merged;
      remove = Set.of();
      newSources = Set.of();
      if (r.grew().isEmpty()) {
        copyTree(merged, out);
        System.out.println("Converged after " + round + " round(s): " + r.resources() + " resources in " + out);
        return;
      }
      System.out.println("Cone grew by " + r.grew().size() + " after merge; rebuilding those");
      rebuild = r.grew();
    }
    System.err.println("Did not converge in " + maxRounds + " rounds. Run a full build.");
    System.exit(3);
  }

  static Set<String> strings(JsonObject o, String name) {
    Set<String> s = new TreeSet<>();
    for (JsonElement je : o.getJsonArray(name)) {
      s.add(je.asString());
    }
    return s;
  }

  static Set<String> allExcept(Path ast, Set<String> rebuild, Set<String> remove) throws Exception {
    Set<String> s = new TreeSet<>();
    for (JsonElement je : manifest(ast).getJsonArray("resources")) {
      String k = je.asJsonObject().asString("key");
      if (!rebuild.contains(k) && !remove.contains(k)) {
        s.add(k);
      }
    }
    return s;
  }

  static Map<String, String> sources(Path ast) throws Exception {
    Map<String, String> m = new HashMap<>();
    for (JsonElement je : manifest(ast).getJsonArray("resources")) {
      JsonObject r = je.asJsonObject();
      if (r.has("source") && !r.get("source").isJsonNull()) {
        m.put(r.asString("key"), r.asString("source"));
      }
    }
    return m;
  }

  static Set<String> libraryNames(Path ast, Set<String> keys) throws Exception {
    Set<String> s = new TreeSet<>();
    for (JsonElement je : manifest(ast).getJsonArray("resources")) {
      JsonObject r = je.asJsonObject();
      if ("Library".equals(r.asString("resourceType")) && keys.contains(r.asString("key")) && r.has("name")
          && !r.get("name").isJsonNull()) {
        s.add(r.asString("name"));
      }
    }
    return s;
  }

  /** A new .fsh file's outputs, found through head's fsh-index.json. */
  static void copyNewSourceOutputs(Path ig, Path roundDir, String fshPath) throws Exception {
    Path idx = ig.resolve("fsh-generated/fsh-index.json");
    if (!Files.exists(idx)) {
      return;
    }
    for (JsonElement je : (org.hl7.fhir.utilities.json.model.JsonArray) JsonParser.parse(Files.readString(idx))) {
      JsonObject o = je.asJsonObject();
      String f = o.asString("fshFile");
      if (fshPath.endsWith(f)) {
        String rel = "fsh-generated/resources/" + o.asString("outputFile");
        Path t = roundDir.resolve(rel);
        Files.createDirectories(t.getParent());
        Files.copy(ig.resolve(rel), t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  static JsonObject manifest(Path ast) throws Exception {
    return JsonParser.parseObject(Files.readString(ast.resolve("manifest.json")));
  }

  static void copyTree(Path from, Path to) throws Exception {
    try (var s = Files.walk(from)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        Path t = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(t);
        } else {
          Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  static String shortRev(String rev) {
    return rev == null ? "unknown" : rev.substring(0, Math.min(8, rev.length()));
  }

  static String orElse(String v, String d) {
    return v == null ? d : v;
  }
}
