package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * Given a base AST and a delta of changed files, decides WHAT must be
 * rebuilt, what must be loaded from the cache to rebuild it, and what is
 * removed — or that a full build is needed, and why.
 *
 * <p>Three rules it will not bend:
 * <ul>
 *   <li><b>"Cannot tell" is never incremental.</b> A changed file that maps
 *       to no resource and is not known to be render-only forces a full
 *       build, with the reason.</li>
 *   <li><b>The cone is FORWARD.</b> Everything that depends on a changed
 *       resource is rebuilt, transitively; what the rebuilt set depends on
 *       is only loaded.</li>
 *   <li><b>The cone is provisional until rebuilt.</b> It is computed on the
 *       BASE edges. A rebuilt resource may gain edges the base did not have,
 *       so the rebuild step must recompute the cone and iterate to a fixed
 *       point. The plan says so.</li>
 * </ul>
 */
public final class IncrementalPlan {

  /** Above this share of the IG, a full build is as cheap and simpler. */
  public static final double DEFAULT_THRESHOLD = 0.4;

  /** Files whose change reconfigures the whole build. */
  static final Set<String> CONFIG = Set.of("sushi-config.yaml", "ig.ini", "package.json", "publication-request.json");

  /** Directories whose files feed rendering only, never a resource. */
  static final List<String> RENDER_ONLY = List.of("input/pagecontent/", "input/pages/", "input/intro-notes/",
      "input/images/", "input/includes/", "input/data/");

  public enum Effect {
    RESOURCES, NEW_SOURCE, RENDER_ONLY, FULL_BUILD
  }

  public record FileResult(AstDelta.Change change, Effect effect, Set<String> resources, String reason) {
  }

  private record Entry(String key, String type, String name, String source) {
  }

  private final List<Entry> entries = new ArrayList<>();
  private final Map<String, Set<String>> dependents = new HashMap<>();
  private final Map<String, Set<String>> dependencies = new HashMap<>();
  /** fsh file (as SUSHI records it) → output file names. */
  private final Map<String, Set<String>> fshOutputs = new HashMap<>();
  /** fsh file → fsh files that insert from it (from fsh-cone); optional. */
  private final Map<String, Set<String>> fshUsers;

  public IncrementalPlan(Path astDir, Map<String, Set<String>> fshUsers) throws IOException {
    this.fshUsers = fshUsers == null ? Map.of() : fshUsers;
    JsonObject m = JsonParser.parseObject(Files.readString(astDir.resolve("manifest.json")));
    for (JsonElement je : m.getJsonArray("resources")) {
      JsonObject o = je.asJsonObject();
      entries.add(new Entry(o.asString("key"), o.asString("resourceType"), str(o, "name"), str(o, "source")));
    }
    Path deps = astDir.resolve("dependencies.json");
    if (Files.exists(deps)) {
      for (JsonElement je : JsonParser.parseObject(Files.readString(deps)).getJsonArray("dependencies")) {
        JsonObject o = je.asJsonObject();
        String to = str(o, "resolved");
        if (to != null) {
          String from = o.asString("source");
          dependents.computeIfAbsent(to, k -> new TreeSet<>()).add(from);
          dependencies.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
        }
      }
    }
    Path idx = astDir.resolve("fsh-index.json");
    if (Files.exists(idx)) {
      JsonArray arr = (JsonArray) JsonParser.parse(Files.readString(idx));
      for (JsonElement je : arr) {
        JsonObject o = je.asJsonObject();
        String fsh = str(o, "fshFile");
        String outFile = str(o, "outputFile");
        if (fsh != null && outFile != null) {
          fshOutputs.computeIfAbsent(norm(fsh), k -> new TreeSet<>()).add(norm(outFile));
        }
      }
    }
  }

  /** Classifies one changed file. Package-visible for tests. */
  FileResult classify(AstDelta.Change c) {
    String path = norm(c.path());
    String base = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    if (CONFIG.contains(path) || CONFIG.contains(base) && !path.contains("/")) {
      return new FileResult(c, Effect.FULL_BUILD, Set.of(), "build configuration changed");
    }
    for (String r : RENDER_ONLY) {
      if (path.startsWith(r)) {
        return new FileResult(c, Effect.RENDER_ONLY, Set.of(), "feeds rendering only (" + r + ")");
      }
    }
    Set<String> hits = resourcesFor(path, new LinkedHashSet<>());
    if (!hits.isEmpty()) {
      return new FileResult(c, Effect.RESOURCES, hits, null);
    }
    boolean source = path.endsWith(".fsh") || path.endsWith(".cql") || path.startsWith("input/");
    if (c.status() == 'A' && source) {
      return new FileResult(c, Effect.NEW_SOURCE, Set.of(),
          "new file: built fresh; its dependents are known only after it is built");
    }
    if (path.endsWith(".fsh") && fshOutputs.keySet().stream().noneMatch(path::endsWith)) {
      return new FileResult(c, Effect.FULL_BUILD, Set.of(),
          "FSH file declares no resource (RuleSet or Alias?) and no fsh-cone users were supplied");
    }
    return new FileResult(c, Effect.FULL_BUILD, Set.of(), "cannot tell what this file affects");
  }

  private Set<String> resourcesFor(String path, Set<String> seen) {
    Set<String> hits = new LinkedHashSet<>();
    if (!seen.add(path)) {
      return hits;
    }
    for (Entry e : entries) {
      if (e.source() != null && (norm(e.source()).equals(path) || norm(e.source()).endsWith("/" + path))) {
        hits.add(e.key());
      }
    }
    if (path.endsWith(".fsh")) {
      for (Map.Entry<String, Set<String>> f : fshOutputs.entrySet()) {
        if (path.equals(f.getKey()) || path.endsWith("/" + f.getKey())) {
          for (String outFile : f.getValue()) {
            for (Entry e : entries) {
              if (e.source() != null && norm(e.source()).endsWith(outFile)) {
                hits.add(e.key());
              }
            }
          }
        }
      }
      for (Map.Entry<String, Set<String>> u : fshUsers.entrySet()) {
        if (path.equals(norm(u.getKey())) || path.endsWith("/" + norm(u.getKey()))) {
          for (String user : u.getValue()) {
            hits.addAll(resourcesFor(norm(user), seen));
          }
        }
      }
    }
    if (path.endsWith(".cql")) {
      String lib = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
      for (Entry e : entries) {
        if ("Library".equals(e.type()) && lib.equals(e.name())) {
          hits.add(e.key());
        }
      }
    }
    return hits;
  }

  /** Everything that depends on {@code seeds}, transitively, seeds included. */
  Set<String> forwardCone(Set<String> seeds) {
    return closure(seeds, dependents);
  }

  /** Everything {@code set} depends on, transitively, minus the set itself. */
  Set<String> toLoad(Set<String> set) {
    Set<String> all = closure(set, dependencies);
    all.removeAll(set);
    return all;
  }

  private static Set<String> closure(Set<String> seeds, Map<String, Set<String>> adj) {
    Set<String> out = new TreeSet<>(seeds);
    Deque<String> q = new ArrayDeque<>(seeds);
    while (!q.isEmpty()) {
      for (String n : adj.getOrDefault(q.pop(), Set.of())) {
        if (out.add(n)) {
          q.push(n);
        }
      }
    }
    return out;
  }

  /** The plan for {@code changes}, as JSON. */
  public JsonObject plan(List<AstDelta.Change> changes, double threshold) {
    List<FileResult> results = new ArrayList<>();
    for (AstDelta.Change c : changes) {
      if (c.status() == 'R') {
        results.add(classify(new AstDelta.Change('D', c.oldPath(), null)));
        results.add(classify(new AstDelta.Change('A', c.path(), null)));
      } else {
        results.add(classify(c));
      }
    }
    Set<String> seeds = new TreeSet<>();
    Set<String> removed = new TreeSet<>();
    List<String> fullBecause = new ArrayList<>();
    for (FileResult r : results) {
      seeds.addAll(r.resources());
      if (r.change().status() == 'D') {
        removed.addAll(r.resources());
      }
      if (r.effect() == Effect.FULL_BUILD) {
        fullBecause.add(r.change().path() + ": " + r.reason());
      }
    }
    Set<String> rebuild = forwardCone(seeds);
    rebuild.removeAll(removed);
    Set<String> load = toLoad(rebuild);
    load.removeAll(removed);
    int total = entries.size();
    double fraction = total == 0 ? 0 : (double) forwardCone(seeds).size() / total;
    if (fraction > threshold) {
      fullBecause.add(String.format("cone is %.1f%% of the IG, above the %.0f%% threshold", 100 * fraction,
          100 * threshold));
    }

    JsonObject p = new JsonObject();
    p.add("$schema", "ig-ast-plan/v1");
    p.add("decision", fullBecause.isEmpty() ? "incremental" : "full");
    JsonArray why = new JsonArray();
    fullBecause.forEach(why::add);
    p.add("fullBuildBecause", why);
    p.add("resourcesInBase", total);
    p.add("coneFraction", new org.hl7.fhir.utilities.json.model.JsonNumber(String.format(java.util.Locale.ROOT, "%.3f", fraction)));
    p.add("seeds", arr(seeds));
    p.add("rebuild", arr(rebuild));
    p.add("remove", arr(removed));
    p.add("loadFromCache", arr(load));
    p.add("provisional", "the cone is computed on the BASE edges; after rebuilding, recompute it with the new "
        + "edges and repeat until it stops growing");
    JsonArray files = new JsonArray();
    for (FileResult r : results) {
      JsonObject o = new JsonObject();
      o.add("path", r.change().path());
      o.add("status", String.valueOf(r.change().status()));
      o.add("effect", r.effect().name());
      o.add("resources", arr(r.resources()));
      if (r.reason() != null) {
        o.add("reason", r.reason());
      }
      files.add(o);
    }
    p.add("files", files);
    return p;
  }

  private static JsonArray arr(Set<String> s) {
    JsonArray a = new JsonArray();
    s.forEach(a::add);
    return a;
  }

  private static String str(JsonObject o, String name) {
    return o.has(name) && !o.get(name).isJsonNull() ? o.asString(name) : null;
  }

  static String norm(String p) {
    String s = p.replace('\\', '/');
    return s.startsWith("./") ? s.substring(2) : s;
  }
}
