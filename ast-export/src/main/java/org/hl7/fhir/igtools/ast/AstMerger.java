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
      String baseRevision, String headRevision, JsonObject headInputs, Path outDir) throws IOException {
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
    Path fshIndex = partialDir.resolve("fsh-index.json");
    if (Files.exists(fshIndex)) {
      Files.copy(fshIndex, outDir.resolve("fsh-index.json"), StandardCopyOption.REPLACE_EXISTING);
    } else if (Files.exists(baseDir.resolve("fsh-index.json"))) {
      Files.copy(baseDir.resolve("fsh-index.json"), outDir.resolve("fsh-index.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    // Fixed point: what the merged graph says depends on what was rebuilt.
    Set<String> cone = new IncrementalPlan(outDir, null).forwardCone(rebuilt);
    cone.removeAll(rebuilt);
    cone.removeAll(rebuild);
    return new Result(list.size(), rebuilt, cone);
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
