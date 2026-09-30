package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

  public AstExporter(Composer composer) {
    this.composer = composer;
  }

  /** One exported resource, as listed in the manifest. */
  public record Entry(String key, String canonical, String version, String resourceType, String id,
      String file, String source) {
  }

  /**
   * @param files     the Publisher's {@code getFileList()}
   * @param outDir    where to write; created if absent
   * @param header    manifest fields describing the build (ig, toolchain, inputs)
   * @return the entries written, in manifest order
   */
  public List<Entry> export(List<FetchedFile> files, Path outDir, JsonObject header) throws IOException {
    List<Entry> entries = new ArrayList<>();
    for (FetchedFile f : files) {
      for (FetchedResource r : f.getResources()) {
        entries.add(write(f, r, outDir));
      }
    }
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
      o.add("file", e.file());
      addOrNull(o, "source", e.source());
      list.add(o);
    }
    manifest.add("resources", list);
    Files.createDirectories(outDir);
    Files.writeString(outDir.resolve("manifest.json"), JsonParser.compose(manifest, true));
    return entries;
  }

  private Entry write(FetchedFile f, FetchedResource r, Path outDir) throws IOException {
    String type = r.fhirType();
    String id = r.getId();
    Element e = r.getElement();
    String canonical = childValue(e, "url");
    String version = childValue(e, "version");
    String rel = "resources/" + safe(type) + "/" + safe(id) + ".json";
    Path target = outDir.resolve(rel);
    Files.createDirectories(target.getParent());
    Files.write(target, composer.compose(r));
    return new Entry(keyOf(type, id, canonical, version), canonical, version, type, id, rel, sourceOf(f));
  }

  /** The file a resource came from, relative to the IG root when the fetcher recorded that. */
  static String sourceOf(FetchedFile f) {
    return f.getRelativePath() != null ? f.getRelativePath() : f.getStatedPath();
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
