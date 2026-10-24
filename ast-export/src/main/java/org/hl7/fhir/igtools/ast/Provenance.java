package org.hl7.fhir.igtools.ast;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonElement;
import org.hl7.fhir.utilities.json.model.JsonObject;

/**
 * Criteria 4 and 5 of bean a9tx (W3): the package closure the build loaded,
 * pinned, and where each ValueSet expansion came from. Both are read from
 * what the Publisher left in memory; nothing is fetched again.
 *
 * <p>Like the rest of the AST this is a cache, never an authority.
 */
public final class Provenance {

  private Provenance() {
  }

  /** One loaded package, as {@code NpmPackage} reports it. */
  public record Pkg(String id, String version, String canonical, String fhirVersion, List<String> dependencies) {
  }

  /**
   * One expansion: the ValueSet's canonical and version, and its expansion
   * parameters (name → values). The expansion's timestamp is left out: it
   * changes every build and says nothing about what the expansion came from.
   */
  public record Expansion(String url, String version, Map<String, List<String>> parameters) {
  }

  /** The parameters that say what an expansion was built from. Others (count, offset...) say how it was asked for. */
  static final List<String> SOURCE_PARAMETERS = List.of("used-codesystem", "used-valueset", "version",
      "used-supplement", "system-version", "force-system-version", "check-system-version");

  /**
   * The closure, one entry per {@code id#version}, sorted, without the
   * incremental build's own cache package (it is the IG itself, not a dependency).
   */
  public static JsonArray packages(Collection<Pkg> loaded, String excludeId) {
    Map<String, Pkg> byKey = new TreeMap<>();
    for (Pkg p : loaded) {
      if (p == null || p.id() == null || p.id().equals(excludeId)) {
        continue;
      }
      byKey.putIfAbsent(p.id() + "#" + p.version(), p);
    }
    JsonArray a = new JsonArray();
    for (Pkg p : byKey.values()) {
      JsonObject o = new JsonObject().add("id", p.id());
      put(o, "version", p.version());
      put(o, "canonical", p.canonical());
      put(o, "fhirVersion", p.fhirVersion());
      JsonArray deps = new JsonArray();
      if (p.dependencies() != null) {
        p.dependencies().stream().sorted().forEach(deps::add);
      }
      o.add("dependencies", deps);
      a.add(o);
    }
    return a;
  }

  /**
   * {@code {server: {address, version}, expansions: [...]}}. The server is the
   * one the build was connected to; an expansion may still have been answered
   * from the local terminology cache, which the Publisher does not record.
   */
  public static JsonObject terminology(String address, String serverVersion, Collection<Expansion> expansions) {
    JsonObject t = new JsonObject();
    JsonObject server = new JsonObject();
    put(server, "address", address);
    put(server, "version", serverVersion);
    t.add("server", server);
    Map<String, JsonObject> byKey = new TreeMap<>();
    for (Expansion e : expansions) {
      if (e == null || e.url() == null) {
        continue;
      }
      byKey.put(key(e.url(), e.version()), expansion(e));
    }
    JsonArray a = new JsonArray();
    byKey.values().forEach(a::add);
    t.add("expansions", a);
    return t;
  }

  private static JsonObject expansion(Expansion e) {
    JsonObject o = new JsonObject().add("valueSet", key(e.url(), e.version()));
    JsonObject from = new JsonObject();
    if (e.parameters() != null) {
      for (String name : SOURCE_PARAMETERS) {
        List<String> v = e.parameters().get(name);
        if (v != null && !v.isEmpty()) {
          JsonArray vals = new JsonArray();
          v.stream().distinct().sorted().forEach(vals::add);
          from.add(name, vals);
        }
      }
    }
    o.add("builtFrom", from);
    return o;
  }

  /**
   * Merges an incremental round's terminology into the base's: the head's
   * server, the base's expansions, and the head's where both have one.
   */
  public static JsonObject mergeTerminology(JsonObject base, JsonObject head) {
    if (head == null) {
      return base;
    }
    if (base == null) {
      return head;
    }
    Map<String, JsonElement> byKey = new TreeMap<>();
    for (JsonObject src : List.of(base, head)) {
      if (src.has("expansions")) {
        for (JsonElement je : src.getJsonArray("expansions")) {
          byKey.put(je.asJsonObject().asString("valueSet"), je);
        }
      }
    }
    JsonObject t = new JsonObject();
    t.add("server", head.has("server") ? head.get("server") : base.get("server"));
    JsonArray a = new JsonArray();
    byKey.values().forEach(a::add);
    t.add("expansions", a);
    return t;
  }

  static String key(String url, String version) {
    return version == null || version.isEmpty() ? url : url + "|" + version;
  }

  private static void put(JsonObject o, String name, String value) {
    if (value != null) {
      o.add(name, value);
    }
  }
}
