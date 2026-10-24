package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * Criterion 3 of bean a9tx (W4), at fragment level (owner, 2026-10-24,
 * option A): which source produced which page fragment, and which page
 * includes which fragment. Written to {@code pages.json} beside the manifest.
 *
 * <p>The source → output half is the Publisher's own record: every file it
 * writes for a source goes into that source's {@code FetchedFile.getOutputNames()}.
 * The page → fragment half is read from the pages the Publisher wrote to its
 * temp folder, by their literal {@code {% include X %}} tags, followed through
 * fragments that include fragments. An include built from a variable cannot be
 * resolved this way and is counted, not guessed.
 *
 * <p>No element-level provenance: that needs a fork of the core renderers.
 */
public final class PageProvenance {

  public static final String FILE = "pages.json";
  public static final String SCHEMA = "ig-ast-pages/v1";

  private PageProvenance() {
  }

  /** One source file, the resources it holds, and every file the Publisher wrote for it. */
  public record Source(String path, List<String> resources, Collection<String> outputs) {
  }

  static final Pattern INCLUDE = Pattern.compile("\\{%-?\\s*include\\s+([^\\s%}]+)");

  /**
   * @param root    the IG root, for source paths
   * @param tempDir the Publisher's temp folder, for output paths and page scanning
   */
  public static JsonObject build(Path root, Path tempDir, List<Source> sources) throws IOException {
    Path includes = tempDir.resolve("_includes");
    JsonObject fragments = new JsonObject();
    JsonObject pages = new JsonObject();
    Map<String, JsonObject> frag = new TreeMap<>();
    Map<String, JsonObject> page = new TreeMap<>();
    for (Source s : sources) {
      String src = rel(root, s.path());
      for (String out : s.outputs()) {
        Path p = Path.of(out).toAbsolutePath().normalize();
        if (!p.startsWith(tempDir.toAbsolutePath().normalize())) {
          continue; // qa copies and other outputs outside the site
        }
        JsonObject o = new JsonObject().add("source", src).add("resources", arr(s.resources()));
        if (p.startsWith(includes.toAbsolutePath().normalize())) {
          frag.put(includes.toAbsolutePath().normalize().relativize(p).toString().replace('\\', '/'), o);
        } else if (isPage(p)) {
          page.put(tempDir.toAbsolutePath().normalize().relativize(p).toString().replace('\\', '/'), o);
        }
      }
    }
    // Every page in the temp folder, whether a source wrote it or not (narrative pages, the template's own).
    int unresolved = 0;
    Map<String, Set<String>> direct = new TreeMap<>();
    if (Files.isDirectory(tempDir)) {
      try (Stream<Path> walk = Files.walk(tempDir)) {
        for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
          String r = tempDir.relativize(p).toString().replace('\\', '/');
          boolean isInclude = p.startsWith(includes);
          if (!isInclude && !isPage(p)) {
            continue;
          }
          Set<String> inc = new TreeSet<>();
          unresolved += includesOf(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8), inc);
          direct.put(isInclude ? "_includes/" + includes.relativize(p).toString().replace('\\', '/') : r, inc);
          if (!isInclude) {
            page.computeIfAbsent(r, k -> new JsonObject());
          }
        }
      }
    }
    for (Map.Entry<String, JsonObject> e : page.entrySet()) {
      Set<String> closure = closure(direct, e.getKey());
      JsonArray uses = new JsonArray();
      closure.stream().filter(frag::containsKey).forEach(uses::add);
      e.getValue().add("fragments", uses);
      pages.add(e.getKey(), e.getValue());
    }
    frag.forEach(fragments::add);
    return new JsonObject().add("$schema", SCHEMA).add("authority", "cache")
        .add("fragments", fragments).add("pages", pages).add("unresolvedIncludes", unresolved);
  }

  /** Literal include names in a page, added to {@code into}; returns how many were built from a variable. */
  static int includesOf(String text, Set<String> into) {
    int unresolved = 0;
    Matcher m = INCLUDE.matcher(text);
    while (m.find()) {
      String n = m.group(1);
      if (n.contains("{{") || n.contains("[")) {
        unresolved++;
      } else {
        into.add(n);
      }
    }
    return unresolved;
  }

  /** Fragment names reachable from a page, through fragments that include fragments. */
  static Set<String> closure(Map<String, Set<String>> direct, String page) {
    Set<String> seen = new TreeSet<>();
    Deque<String> todo = new ArrayDeque<>(direct.getOrDefault(page, Set.of()));
    while (!todo.isEmpty()) {
      String n = todo.pop();
      if (seen.add(n)) {
        todo.addAll(direct.getOrDefault("_includes/" + n, Set.of()));
      }
    }
    return seen;
  }

  static boolean isPage(Path p) {
    String n = p.getFileName().toString().toLowerCase();
    return n.endsWith(".html") || n.endsWith(".md");
  }

  /**
   * An incremental round's pages.json over the base's: a source the round
   * rebuilt takes the round's fragments and pages; everything else, and every
   * page the round did not write from a source (its stub pages), stays the base's.
   */
  public static JsonObject merge(JsonObject base, JsonObject part) {
    if (part == null) {
      return base;
    }
    if (base == null) {
      return part;
    }
    Set<String> rebuiltSources = new TreeSet<>();
    for (String section : List.of("fragments", "pages")) {
      for (JsonElement je : objOf(part, section).getProperties().stream().map(p -> p.getValue()).toList()) {
        if (je.asJsonObject().has("source")) {
          rebuiltSources.add(je.asJsonObject().asString("source"));
        }
      }
    }
    JsonObject out = new JsonObject().add("$schema", SCHEMA).add("authority", "cache");
    for (String section : List.of("fragments", "pages")) {
      Map<String, JsonElement> m = new TreeMap<>();
      JsonObject b = objOf(base, section);
      for (String k : b.getNames()) {
        JsonObject o = b.getJsonObject(k);
        if (!o.has("source") || !rebuiltSources.contains(o.asString("source"))) {
          m.put(k, o);
        }
      }
      JsonObject p = objOf(part, section);
      for (String k : p.getNames()) {
        if (p.getJsonObject(k).has("source")) {
          m.put(k, p.getJsonObject(k));
        }
      }
      JsonObject s = new JsonObject();
      m.forEach(s::add);
      out.add(section, s);
    }
    out.add("unresolvedIncludes", base.has("unresolvedIncludes") ? base.asInteger("unresolvedIncludes") : 0);
    return out;
  }

  public static JsonObject read(Path astDir) throws IOException {
    Path f = astDir.resolve(FILE);
    return Files.exists(f) ? JsonParser.parseObject(Files.readString(f)) : null;
  }

  public static void write(JsonObject o, Path astDir) throws IOException {
    Files.writeString(astDir.resolve(FILE), JsonParser.compose(o, true));
  }

  private static JsonObject objOf(JsonObject o, String name) {
    return o.has(name) ? o.getJsonObject(name) : new JsonObject();
  }

  private static String rel(Path root, String path) {
    if (path == null) {
      return null;
    }
    Path p = Path.of(path).toAbsolutePath().normalize();
    Path r = root.toAbsolutePath().normalize();
    return (p.startsWith(r) ? r.relativize(p) : p).toString().replace('\\', '/');
  }

  private static JsonArray arr(Collection<String> s) {
    JsonArray a = new JsonArray();
    if (s != null) {
      new TreeSet<>(s).forEach(a::add);
    }
    return a;
  }
}
