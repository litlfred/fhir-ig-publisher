package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.igtools.publisher.FetchedFile;
import org.hl7.fhir.igtools.publisher.FetchedResource;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * Writes the resources a Publisher run holds in memory as a per-resource
 * dump, keyed by {@code canonical|version}, plus a manifest.
 *
 * <p><b>An AST is a CACHE, never an authority.</b> The manifest says so in
 * {@code authority} and lists what is {@code provisional} until a full
 * Publisher run: indices, dependency edges and versions. A consumer that
 * renders from it must carry that mark to the reader.
 *
 * <p>It only reads the Publisher's state and only writes under its own
 * output directory, so the Publisher's {@code output/} is untouched.
 */
public class AstExporter {

  public static final String SCHEMA = "ig-ast/v1";

  /** Turns one loaded resource into its JSON bytes. */
  @FunctionalInterface
  public interface Composer {
    byte[] compose(FetchedResource r) throws IOException;
  }

  private final Composer composer;
  /** The IG root, against which an absolute source path is made relative; may be null. */
  private final Path root;

  public AstExporter(Composer composer) {
    this(composer, null);
  }

  public AstExporter(Composer composer, Path root) {
    this.composer = composer;
    this.root = root == null ? null : root.toAbsolutePath().normalize();
  }

  /**
   * One dependency edge, from an exported resource to a canonical.
   * {@code origin} says which analyser claimed it — {@code publisher} for
   * upstream's DependencyAnalyser, {@code ast-export} for {@link LogicEdges}.
   */
  public record EdgeRow(String source, String kind, String target, String targetVersion, String path,
      String origin) {
  }

  /** One exported resource, as listed in the manifest. */
  public record Entry(String key, String canonical, String version, String resourceType, String id,
      String name, String file, String source) {
  }

  /**
   * @param files     the Publisher's {@code getFileList()}
   * @param outDir    where to write; created if absent
   * @param header    manifest fields describing the build (ig, toolchain, inputs)
   * @return the entries written, in manifest order
   */
  public List<Entry> export(List<FetchedFile> files, Path outDir, JsonObject header) throws IOException {
    return export(files, outDir, header, List.of());
  }

  /**
   * @param upstreamEdges edges another analyser already found (upstream's
   *                      DependencyAnalyser), written beside this library's own
   */
  public List<Entry> export(List<FetchedFile> files, Path outDir, JsonObject header, List<EdgeRow> upstreamEdges)
      throws IOException {
    List<Entry> entries = new ArrayList<>();
    List<EdgeRow> edges = new ArrayList<>(upstreamEdges);
    for (FetchedFile f : files) {
      for (FetchedResource r : f.getResources()) {
        Entry entry = write(f, r, outDir);
        entries.add(entry);
        for (LogicEdges.Edge e : LogicEdges.of(entry.resourceType(), r.getElement())) {
          edges.add(new EdgeRow(entry.key(), e.kind(), e.target(), e.targetVersion(), e.path(), "ast-export"));
        }
      }
    }
    writeDependencies(entries, edges, outDir);
    entries.sort(Comparator.comparing(Entry::key).thenComparing(Entry::file));
    JsonObject manifest = new JsonObject();
    manifest.add("$schema", SCHEMA);
    manifest.add("authority", "cache");
    JsonArray provisional = new JsonArray();
    provisional.add("indices");
    provisional.add("dependencies");
    provisional.add("versions");
    manifest.add("provisional", provisional);
    manifest.add("provisionalUntil", "a full IG Publisher run");
    for (String name : header.getNames()) {
      manifest.add(name, header.get(name));
    }
    JsonArray list = new JsonArray();
    for (Entry e : entries) {
      JsonObject o = new JsonObject();
      o.add("key", e.key());
      addOrNull(o, "canonical", e.canonical());
      addOrNull(o, "version", e.version());
      o.add("resourceType", e.resourceType());
      o.add("id", e.id());
      addOrNull(o, "name", e.name());
      o.add("file", e.file());
      addOrNull(o, "source", e.source());
      list.add(o);
    }
    manifest.add("resources", list);
    Files.createDirectories(outDir);
    Files.writeString(outDir.resolve("manifest.json"), JsonParser.compose(manifest, true));
    return entries;
  }

  /**
   * dependencies.json: every edge, with {@code resolved} naming the exported
   * resource it lands on, or null when the target is outside this IG. An
   * unresolved edge is kept — see {@link LogicEdges}.
   */
  static void writeDependencies(List<Entry> entries, List<EdgeRow> edges, Path outDir) throws IOException {
    Map<String, String> byCanonical = new HashMap<>();
    for (Entry e : entries) {
      if (e.canonical() != null) {
        byCanonical.putIfAbsent(e.canonical(), e.key());
        if (e.version() != null) {
          byCanonical.put(e.canonical() + "|" + e.version(), e.key());
        }
      }
    }
    List<EdgeRow> sorted = new ArrayList<>(edges);
    sorted.sort(Comparator.comparing(EdgeRow::source).thenComparing(EdgeRow::kind).thenComparing(EdgeRow::target)
        .thenComparing(r -> r.path() == null ? "" : r.path()));
    JsonArray list = new JsonArray();
    int resolved = 0;
    for (EdgeRow r : sorted) {
      JsonObject o = new JsonObject();
      o.add("source", r.source());
      o.add("kind", r.kind());
      o.add("target", r.target());
      addOrNull(o, "targetVersion", r.targetVersion());
      String hit = r.targetVersion() != null ? byCanonical.get(r.target() + "|" + r.targetVersion()) : null;
      if (hit == null) {
        hit = byCanonical.get(r.target());
      }
      addOrNull(o, "resolved", hit);
      if (hit != null) {
        resolved++;
      }
      addOrNull(o, "path", r.path());
      o.add("origin", r.origin());
      list.add(o);
    }
    JsonObject doc = new JsonObject();
    doc.add("$schema", "ig-ast-dependencies/v1");
    doc.add("authority", "cache");
    doc.add("edges", list.size());
    doc.add("resolvedInIg", resolved);
    doc.add("dependencies", list);
    Files.createDirectories(outDir);
    Files.writeString(outDir.resolve("dependencies.json"), JsonParser.compose(doc, true));
  }

  private Entry write(FetchedFile f, FetchedResource r, Path outDir) throws IOException {
    String type = r.fhirType();
    String id = r.getId();
    Element e = r.getElement();
    String canonical = childValue(e, "url");
    String version = childValue(e, "version");
    String name = childValue(e, "name");
    String key = keyOf(type, id, canonical, version);
    // The file name carries a hash of the KEY: two versions (or canonicals) of
    // one Type/id are two resources and must be two files (Copilot review on
    // folio-assistant#1708).
    String rel = "resources/" + safe(type) + "/" + fileName(id, key);
    Path target = outDir.resolve(rel);
    Files.createDirectories(target.getParent());
    Files.write(target, composer.compose(r));
    return new Entry(key, canonical, version, type, id, name, rel, sourceOf(f, root));
  }

  /** {@code <id>--<first 8 hex of sha256(key)>.json}. */
  static String fileName(String id, String key) {
    try {
      byte[] d = java.security.MessageDigest.getInstance("SHA-256")
          .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return safe(id) + "--" + java.util.HexFormat.of().formatHex(d).substring(0, 8) + ".json";
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The file a resource came from, relative to the IG root. Upstream's
   * {@code SimpleFetcher.fetch} records an ABSOLUTE stated path and no
   * relative one for a resource, so an absolute path under {@code root} is
   * made relative here; one outside it is kept as it is. (Review of PR #8,
   * B3: an absolute source made {@code TempIgAssembler}'s copy a no-op.)
   */
  static String sourceOf(FetchedFile f, Path root) {
    String s = f.getRelativePath() != null ? f.getRelativePath() : f.getStatedPath();
    return relativeTo(root, s);
  }

  /** {@code s} relative to {@code root}, with '/' separators, when it is an absolute path under it. */
  static String relativeTo(Path root, String s) {
    if (s == null || root == null) {
      return s;
    }
    Path p = Path.of(s);
    if (p.isAbsolute()) {
      Path n = p.normalize();
      if (n.startsWith(root)) {
        return root.relativize(n).toString().replace('\\', '/');
      }
    }
    return s;
  }

  /** The primitive value of a direct child, or null. Walks the children rather than asking by name, so it needs no Property. */
  static String childValue(Element e, String name) {
    if (e == null || !e.hasChildren()) {
      return null;
    }
    for (Element c : e.getChildren()) {
      if (name.equals(c.getName()) && c.hasValue()) {
        return c.getValue();
      }
    }
    return null;
  }

  /** {@code canonical|version} when the resource has a canonical, else {@code Type/id}. */
  static String keyOf(String type, String id, String canonical, String version) {
    if (canonical == null || canonical.isEmpty()) {
      return type + "/" + id;
    }
    return version == null || version.isEmpty() ? canonical : canonical + "|" + version;
  }

  static String safe(String s) {
    return s == null ? "_" : s.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static void addOrNull(JsonObject o, String name, String value) {
    if (value == null) {
      o.addNull(name);
    } else {
      o.add(name, value);
    }
  }
}
