package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * Assembles a temporary IG holding only the resources to rebuild, from an
 * IG whose SUSHI output ({@code fsh-generated/}) is already current.
 *
 * <p>It is built with {@code -no-sushi}, so the ImplementationGuide resource
 * SUSHI wrote is the one the Publisher reads. That resource is rewritten:
 * {@code definition.resource} keeps only the rebuild set, {@code dependsOn}
 * gains the cache package, and {@code definition.page} is cut to one stub,
 * because an incremental build produces an AST, not a site.
 *
 * <p>Run against a real Publisher on smart-immunizations in W7 rounds 2-6
 * (2026-10-23/24). The risks are named in the README.
 */
public final class TempIgAssembler {

  private TempIgAssembler() {
  }

  /** Directories of files the template's layouts include, copied into the temporary IG whole. */
  static final List<String> INCLUDE_DIRS = List.of("input/includes", "fsh-generated/includes");

  /** What was assembled, for the caller and for tests. */
  public record Result(Path igRoot, Path igResource, List<String> copied, List<String> missing) {
  }

  /**
   * @param igRoot        the real IG, at the head revision, with fsh-generated/ current
   * @param rebuild       the resource keys to rebuild
   * @param sources       resource key → source path relative to igRoot (from the head AST or base manifest)
   * @param libraryNames  names of Libraries in the rebuild set, whose .cql to carry
   * @param cachePackage  {@code name#version} of the cache package
   */
  public static Result assemble(Path igRoot, Path work, Set<String> rebuild, java.util.Map<String, String> sources,
      Set<String> libraryNames, String cachePackage) throws IOException {
    return assemble(igRoot, work, rebuild, sources, libraryNames, cachePackage, List.of());
  }

  /**
   * @param newFiles resource files of NEW sources, relative to igRoot, already
   *                 in {@code work}; listed in the temporary IG like the rest
   */
  public static Result assemble(Path igRoot, Path work, Set<String> rebuild, java.util.Map<String, String> sources,
      Set<String> libraryNames, String cachePackage, List<String> newFiles) throws IOException {
    Files.createDirectories(work);
    List<String> copied = new ArrayList<>();
    for (String f : newFiles) {
      copy(igRoot, work, f);
      copied.add(f);
    }
    List<String> missing = new ArrayList<>();

    Path iniSrc = igRoot.resolve("ig.ini");
    String igRel = igResourcePath(iniSrc);
    Files.copy(iniSrc, work.resolve("ig.ini"), StandardCopyOption.REPLACE_EXISTING);

    Path absRoot = igRoot.toAbsolutePath().normalize();
    for (String key : rebuild) {
      // A base AST written before the B3 fix holds absolute sources; one
      // outside the IG cannot be copied into the temporary IG at all.
      String src = AstExporter.relativeTo(absRoot, sources.get(key));
      if (src == null || Path.of(src).isAbsolute() || !Files.exists(igRoot.resolve(src))) {
        missing.add(key);
        continue;
      }
      copy(igRoot, work, src);
      copied.add(src);
    }
    for (String lib : libraryNames) {
      String cql = "input/cql/" + lib + ".cql";
      if (Files.exists(igRoot.resolve(cql))) {
        copy(igRoot, work, cql);
        copied.add(cql);
      }
    }

    JsonObject ig = JsonParser.parseObject(Files.readString(igRoot.resolve(igRel)));
    rewriteIg(ig, copied, cachePackage);
    Path igOut = work.resolve(igRel);
    Files.createDirectories(igOut.getParent());
    Files.writeString(igOut, JsonParser.compose(ig, true));

    // The template's layouts include files the IG supplies, such as the
    // menu.xml SUSHI writes from sushi-config.yaml. The temporary IG is built
    // with -no-sushi and no pages, so without them Jekyll fails on the first
    // page (bean wv7r, 2026-10-23: "Could not locate the included file
    // 'menu.xml'"). Copied whole: they are small and feed rendering only.
    for (String dir : INCLUDE_DIRS) {
      Path d = igRoot.resolve(dir);
      if (Files.isDirectory(d)) {
        try (java.util.stream.Stream<Path> s = Files.walk(d)) {
          for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
            copy(igRoot, work, igRoot.relativize(f).toString().replace('\\', '/'));
          }
        }
      }
    }

    // Every directory the IG's path-* parameters name exists in the temporary
    // IG too, even when nothing was copied into it: the Publisher reports a
    // missing one as an error (wv7r, fml probe: "path '.../input/cql' is not
    // a valid directory").
    for (String dir : pathParameterDirs(ig)) {
      if (Files.isDirectory(igRoot.resolve(dir)) && !Path.of(dir).isAbsolute() && !dir.contains("..")) {
        Files.createDirectories(work.resolve(dir));
      }
    }

    Path index = work.resolve("input/pagecontent/index.md");
    Files.createDirectories(index.getParent());
    Files.writeString(index, "Incremental AST rebuild. Not a published page.\n");
    return new Result(work, igOut, copied, missing);
  }

  /** The values of the ImplementationGuide's {@code path-*} parameters. */
  static List<String> pathParameterDirs(JsonObject ig) {
    List<String> dirs = new ArrayList<>();
    JsonObject def = ig.getJsonObject("definition");
    if (def == null || !def.has("parameter")) {
      return dirs;
    }
    for (JsonElement je : def.getJsonArray("parameter")) {
      JsonObject p = je.asJsonObject();
      JsonElement code = p.get("code");
      String c = code == null ? null
          : code.isJsonObject() ? code.asJsonObject().asString("code") : code.asString();
      if (c != null && c.startsWith("path-") && p.has("value")) {
        dirs.add(p.asString("value"));
      }
    }
    return dirs;
  }

  /** The {@code ig =} path from ig.ini. */
  static String igResourcePath(Path ini) throws IOException {
    for (String line : Files.readAllLines(ini)) {
      String t = line.trim();
      if (t.startsWith("ig") && t.contains("=") && t.substring(0, t.indexOf('=')).trim().equals("ig")) {
        return t.substring(t.indexOf('=') + 1).trim();
      }
    }
    throw new IOException("ig.ini has no 'ig =' entry: " + ini);
  }

  /**
   * Keeps only the definition.resource entries whose reference names a copied
   * file's resource, adds the cache package to dependsOn, and cuts pages to one.
   */
  static void rewriteIg(JsonObject ig, List<String> copiedFiles, String cachePackage) {
    Set<String> refs = new java.util.HashSet<>();
    for (String f : copiedFiles) {
      String name = f.substring(f.lastIndexOf('/') + 1);
      if (name.endsWith(".json") && name.contains("-")) {
        String stem = name.substring(0, name.length() - 5);
        int dash = stem.indexOf('-');
        refs.add(stem.substring(0, dash) + "/" + stem.substring(dash + 1));
      }
    }
    JsonObject def = ig.getJsonObject("definition");
    if (def != null && def.has("resource")) {
      JsonArray kept = new JsonArray();
      for (JsonElement je : def.getJsonArray("resource")) {
        JsonObject r = je.asJsonObject();
        JsonObject ref = r.getJsonObject("reference");
        if (ref != null && refs.contains(ref.asString("reference"))) {
          kept.add(r);
        }
      }
      def.set("resource", kept);
    }
    if (def != null) {
      JsonObject page = new JsonObject();
      page.add("nameUrl", "index.html");
      page.add("title", "Incremental AST rebuild");
      page.add("generation", "markdown");
      def.set("page", page);
    }
    String[] nv = cachePackage.split("#", 2);
    JsonArray deps = ig.has("dependsOn") ? ig.getJsonArray("dependsOn") : new JsonArray();
    JsonObject dep = new JsonObject();
    dep.add("id", "astcache");
    dep.add("uri", "http://ast-export.invalid/ImplementationGuide/" + nv[0]);
    dep.add("packageId", nv[0]);
    dep.add("version", nv[1]);
    deps.add(dep);
    ig.set("dependsOn", deps);
  }

  private static void copy(Path from, Path to, String rel) throws IOException {
    Path t = to.resolve(rel);
    Files.createDirectories(t.getParent());
    Files.copy(from.resolve(rel), t, StandardCopyOption.REPLACE_EXISTING);
  }
}
