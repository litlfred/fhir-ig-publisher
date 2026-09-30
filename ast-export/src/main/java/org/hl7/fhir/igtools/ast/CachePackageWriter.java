package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * Writes part of a base AST as a FHIR NPM package, directly in the package
 * cache's folder layout ({@code <cache>/<name>#<version>/package/}), so the
 * stock Publisher loads it as an ordinary dependency. Nothing is published
 * to a registry.
 *
 * <p>This is how an incremental rebuild gets the resources it did NOT change:
 * the Publisher already knows how to load a dependency package, so the cached
 * part of the IG becomes one.
 */
public final class CachePackageWriter {

  private CachePackageWriter() {
  }

  /** The package id used for the cached part of {@code igPackageId}. */
  public static String packageName(String igPackageId) {
    return igPackageId + ".ast-cache";
  }

  /**
   * @param keys        the resource keys to include ({@code loadFromCache})
   * @param version     unique per base, e.g. {@code 0.0.0-ast.<short rev>}
   * @param fhirVersion e.g. {@code 4.0.1}
   * @return the package folder written
   */
  public static Path write(Path astDir, Set<String> keys, Path cacheFolder, String name, String version,
      String fhirVersion) throws IOException {
    JsonObject manifest = JsonParser.parseObject(Files.readString(astDir.resolve("manifest.json")));
    Path pkg = cacheFolder.resolve(name + "#" + version).resolve("package");
    Files.createDirectories(pkg);

    JsonArray index = new JsonArray();
    for (JsonElement je : manifest.getJsonArray("resources")) {
      JsonObject r = je.asJsonObject();
      if (!keys.contains(r.asString("key"))) {
        continue;
      }
      String type = r.asString("resourceType");
      String id = r.asString("id");
      String filename = AstExporter.safe(type) + "-" + AstExporter.safe(id) + ".json";
      Files.copy(astDir.resolve(r.asString("file")), pkg.resolve(filename),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      JsonObject entry = new JsonObject();
      entry.add("filename", filename);
      entry.add("resourceType", type);
      entry.add("id", id);
      copyIfPresent(r, entry, Map.of("canonical", "url", "version", "version"));
      index.add(entry);
    }
    JsonObject idx = new JsonObject();
    idx.add("index-version", new org.hl7.fhir.utilities.json.model.JsonNumber("2"));
    idx.add("files", index);
    Files.writeString(pkg.resolve(".index.json"), JsonParser.compose(idx, true));

    JsonObject pj = new JsonObject();
    pj.add("name", name);
    pj.add("version", version);
    pj.add("type", "fhir.ig");
    JsonArray fv = new JsonArray();
    fv.add(fhirVersion);
    pj.add("fhirVersions", fv);
    pj.add("description", "Cached part of an IG, written by ast-export for an incremental rebuild. "
        + "A cache, never an authority.");
    pj.add("dependencies", new JsonObject());
    Files.writeString(pkg.resolve("package.json"), JsonParser.compose(pj, true));
    return pkg.getParent();
  }

  private static void copyIfPresent(JsonObject from, JsonObject to, Map<String, String> names) {
    for (Map.Entry<String, String> n : names.entrySet()) {
      if (from.has(n.getKey()) && !from.get(n.getKey()).isJsonNull()) {
        to.add(n.getValue(), from.asString(n.getKey()));
      }
    }
  }
}
