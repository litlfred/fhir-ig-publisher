package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * Merges a rebuilt partial AST into its base, producing a MIXED-PROVENANCE
 * AST, and reports whether the rebuild set must grow.
 *
 * <ul>
 *   <li>Base resources in {@code remove} or {@code rebuild} are dropped; the
 *       rebuilt ones come from the partial AST, except its ImplementationGuide,
 *       which belongs to the temporary IG.</li>
 *   <li>Every resource carries {@code builtAt}: the revision that built it.
 *       The manifest says {@code mixed: true}.</li>
 *   <li>Edges are re-resolved against the merged set. In the partial AST, an
 *       edge into the cache package resolved to nothing; here it lands.</li>
 *   <li><b>Fixed point.</b> With the MERGED edges, the forward cone of what
 *       was rebuilt may reach resources that were not. They are returned in
 *       {@link Result#grew()}; the caller rebuilds them and merges again,
 *       until nothing grows.</li>
 * </ul>
 */
public final class AstMerger {

  private AstMerger() {
  }

  public record Result(int resources, Set<String> rebuilt, Set<String> grew) {
  }

  public static Result merge(Path baseDir, Path partialDir, Set<String> rebuild, Set<String> remove,
      String baseRevision, String headRevision, JsonObject headInputs, Path outDir)
      throws IOException, FullBuildRequired {
    return merge(baseDir, partialDir, rebuild, remove, baseRevision, headRevision, headInputs, null, outDir);
  }

  /**
   * @param headFshIndex SUSHI's {@code fsh-index.json} at HEAD, after SUSHI ran;
   *                     null when the IG has no FSH
   * @throws FullBuildRequired when a key in {@code rebuild} was not produced by
   *         the partial build and head's fsh-index does not explain it as
   *         removed or renamed (review of PR #8, B3)
   */
  public static Result merge(Path baseDir, Path partialDir, Set<String> rebuild, Set<String> remove,
      String baseRevision, String headRevision, JsonObject headInputs, Path headFshIndex, Path outDir)
      throws IOException, FullBuildRequired {
    JsonObject base = JsonParser.parseObject(Files.readString(baseDir.resolve("manifest.json")));
    JsonObject part = JsonParser.parseObject(Files.readString(partialDir.resolve("manifest.json")));
    Files.createDirectories(outDir);

    List<AstExporter.Entry> entries = new ArrayList<>();
    JsonArray list = new JsonArray();
    Set<String> rebuilt = new TreeSet<>();
    Set<String> kept = new TreeSet<>();
    for (JsonElement je : part.getJsonArray("resources")) {
      JsonObject r = je.asJsonObject();
      if ("ImplementationGuide".equals(r.asString("resourceType"))) {
        continue;
      }
      rebuilt.add(r.asString("key"));
      add(r, partialDir, outDir, headRevision, entries, list);
    }
    Set<String> vanished = unexplainedOrVanished(base, rebuild, remove, rebuilt, headFshIndex);
    remove = new TreeSet<>(remove);
    remove.addAll(vanished);
    for (JsonElement je : base.getJsonArray("resources")) {
      JsonObject r = je.asJsonObject();
      String key = r.asString("key");
      if (remove.contains(key) || rebuild.contains(key) || rebuilt.contains(key)) {
        continue;
      }
      kept.add(key);
      String builtAt = r.has("builtAt") ? r.asString("builtAt") : baseRevision;
      add(r, baseDir, outDir, builtAt, entries, list);
    }

    List<AstExporter.EdgeRow> edges = new ArrayList<>();
    for (JsonElement je : readEdges(baseDir)) {
      JsonObject o = je.asJsonObject();
      if (kept.contains(o.asString("source"))) {
        edges.add(row(o));
      }
    }
    for (JsonElement je : readEdges(partialDir)) {
      JsonObject o = je.asJsonObject();
      if (rebuilt.contains(o.asString("source"))) {
        edges.add(row(o));
      }
    }
    AstExporter.writeDependencies(entries, edges, outDir);

    JsonObject m = new JsonObject();
    m.add("$schema", AstExporter.SCHEMA);
    m.add("authority", "cache");
    m.add("provisional", base.getJsonArray("provisional"));
    m.add("provisionalUntil", "a full IG Publisher run");
    m.add("mixed", true);
    m.add("generatedAt", Instant.now().toString());
    JsonObject lineage = new JsonObject();
    lineage.add("base", baseRevision);
    lineage.add("head", headRevision);
    lineage.add("rebuilt", rebuilt.size());
    lineage.add("kept", kept.size());
    lineage.add("removed", remove.size());
    m.add("incremental", lineage);
    if (base.has("ig")) {
      m.add("ig", base.get("ig"));
    }
    if (part.has("toolchain")) {
      m.add("toolchain", part.get("toolchain"));
    }
    if (headInputs != null) {
      m.add("inputs", headInputs);
    }
    m.add("resources", list);
    Files.writeString(outDir.resolve("manifest.json"), JsonParser.compose(m, true));
    // Review of PR #8, M5: the merged AST describes HEAD, so it carries head's
    // fsh-index, read after SUSHI ran. Without one, it carries none: the base's
    // index would map the next delta through files that may have moved.
    Path outIndex = outDir.resolve("fsh-index.json");
    if (headFshIndex != null && Files.exists(headFshIndex)) {
      Files.copy(headFshIndex, outIndex, StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.deleteIfExists(outIndex);
    }

    // Fixed point: what the merged graph says depends on what was rebuilt.
    Set<String> cone = new IncrementalPlan(outDir, null).forwardCone(rebuilt);
    cone.removeAll(rebuilt);
    cone.removeAll(rebuild);
    return new Result(list.size(), rebuilt, cone);
  }

  /**
   * Keys in {@code rebuild} the partial build did not produce. Each must be
   * explained by head's fsh-index: its base source is a SUSHI output that
   * head no longer generates, so its .fsh definition was removed or renamed.
   * Those are returned, to be removed. Any other is a resource the rebuild
   * lost, and the whole round is abandoned for a full build.
   */
  static Set<String> unexplainedOrVanished(JsonObject base, Set<String> rebuild, Set<String> remove,
      Set<String> rebuilt, Path headFshIndex) throws IOException, FullBuildRequired {
    Set<String> missing = new TreeSet<>(rebuild);
    missing.removeAll(rebuilt);
    missing.removeAll(remove);
    if (missing.isEmpty()) {
      return missing;
    }
    Set<String> headOutputs = new TreeSet<>();
    boolean haveIndex = headFshIndex != null && Files.exists(headFshIndex);
    if (haveIndex) {
      for (JsonElement je : (JsonArray) JsonParser.parse(Files.readString(headFshIndex))) {
        String of = s(je.asJsonObject(), "outputFile");
        if (of != null) {
          headOutputs.add(IncrementalPlan.norm(of));
        }
      }
    }
    Set<String> unexplained = new TreeSet<>();
    for (JsonElement je : base.getJsonArray("resources")) {
      JsonObject r = je.asJsonObject();
      String key = r.asString("key");
      if (!missing.contains(key)) {
        continue;
      }
      String src = s(r, "source");
      String norm = src == null ? null : IncrementalPlan.norm(src);
      int at = norm == null ? -1 : norm.lastIndexOf("fsh-generated/resources/");
      boolean gone = haveIndex && at >= 0
          && !headOutputs.contains(norm.substring(at + "fsh-generated/resources/".length()));
      if (!gone) {
        unexplained.add(key);
      }
    }
    if (!unexplained.isEmpty()) {
      throw new FullBuildRequired("the partial build did not produce " + unexplained.size() + " resource(s) it was "
          + "asked to rebuild, and head's fsh-index does not explain them as removed or renamed: " + unexplained);
    }
    return missing;
  }

  private static void add(JsonObject r, Path from, Path outDir, String builtAt, List<AstExporter.Entry> entries,
      JsonArray list) throws IOException {
    String file = r.asString("file");
    Path target = outDir.resolve(file);
    Files.createDirectories(target.getParent());
    Files.copy(from.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
    JsonObject copy = JsonParser.parseObject(JsonParser.compose(r));
    copy.set("builtAt", builtAt == null ? "unknown" : builtAt);
    list.add(copy);
    entries.add(new AstExporter.Entry(r.asString("key"), s(r, "canonical"), s(r, "version"),
        r.asString("resourceType"), r.asString("id"), s(r, "name"), file, s(r, "source")));
  }

  private static JsonArray readEdges(Path dir) throws IOException {
    Path p = dir.resolve("dependencies.json");
    return Files.exists(p) ? JsonParser.parseObject(Files.readString(p)).getJsonArray("dependencies") : new JsonArray();
  }

  private static AstExporter.EdgeRow row(JsonObject o) {
    return new AstExporter.EdgeRow(o.asString("source"), o.asString("kind"), o.asString("target"),
        s(o, "targetVersion"), s(o, "path"), o.asString("origin"));
  }

  private static String s(JsonObject o, String n) {
    return o.has(n) && !o.get(n).isJsonNull() ? o.asString(n) : null;
  }
}
