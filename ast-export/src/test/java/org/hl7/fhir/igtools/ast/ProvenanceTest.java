package org.hl7.fhir.igtools.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.junit.jupiter.api.Test;

/** W3 (bean a9tx criteria 4 and 5). */
class ProvenanceTest {

  @Test
  void theClosureIsPinnedSortedAndWithoutTheCachePackage() {
    JsonArray a = Provenance.packages(List.of(
        new Provenance.Pkg("hl7.fhir.uv.cpg", "2.0.0", "http://hl7.org/fhir/uv/cpg", "4.0.1", List.of("hl7.fhir.uv.cql#2.0.0", "hl7.fhir.r4.core#4.0.1")),
        new Provenance.Pkg("hl7.fhir.r4.core", "4.0.1", "http://hl7.org/fhir", "4.0.1", List.of()),
        new Provenance.Pkg("hl7.fhir.r4.core", "4.0.1", "http://hl7.org/fhir", "4.0.1", List.of()),
        new Provenance.Pkg("my.ig.ast-cache", "0.0.0-ast.abc.r1", "http://x", "4.0.1", List.of())),
        "my.ig.ast-cache");
    assertEquals(2, a.size(), "one entry per id#version, cache package left out");
    JsonObject first = a.get(0).asJsonObject();
    assertEquals("hl7.fhir.r4.core", first.asString("id"));
    assertEquals("4.0.1", first.asString("version"));
    JsonArray deps = a.get(1).asJsonObject().getJsonArray("dependencies");
    assertEquals("hl7.fhir.r4.core#4.0.1", deps.get(0).asString(), "dependencies sorted");
  }

  @Test
  void anExpansionSaysWhatItWasBuiltFromAndNotWhen() {
    JsonObject t = Provenance.terminology("http://tx.fhir.org/r4", "1.9.0", List.of(
        new Provenance.Expansion("http://x/ValueSet/b", "1.0", Map.of(
            "used-codesystem", List.of("http://snomed.info/sct|http://snomed.info/sct/900000000000207008/version/20240201"),
            "count", List.of("1000"))),
        new Provenance.Expansion("http://x/ValueSet/a", null, Map.of())));
    assertEquals("http://tx.fhir.org/r4", t.getJsonObject("server").asString("address"));
    assertEquals("1.9.0", t.getJsonObject("server").asString("version"));
    JsonArray e = t.getJsonArray("expansions");
    assertEquals("http://x/ValueSet/a", e.get(0).asJsonObject().asString("valueSet"), "sorted by valueSet");
    JsonObject b = e.get(1).asJsonObject();
    assertEquals("http://x/ValueSet/b|1.0", b.asString("valueSet"));
    assertEquals(1, b.getJsonObject("builtFrom").getJsonArray("used-codesystem").size());
    assertFalse(b.getJsonObject("builtFrom").has("count"), "how it was asked for is not provenance");
    assertFalse(b.has("timestamp"), "a timestamp would make every build differ");
  }

  @Test
  void anIncrementalRoundKeepsTheBasesExpansionsAndOverridesItsOwn() throws Exception {
    JsonObject base = Provenance.terminology("http://tx/a", "1", List.of(
        new Provenance.Expansion("http://x/ValueSet/a", null, Map.of("version", List.of("old"))),
        new Provenance.Expansion("http://x/ValueSet/b", null, Map.of())));
    JsonObject head = Provenance.terminology("http://tx/b", "2", List.of(
        new Provenance.Expansion("http://x/ValueSet/a", null, Map.of("version", List.of("new")))));
    JsonObject m = Provenance.mergeTerminology(base, head);
    assertEquals("http://tx/b", m.getJsonObject("server").asString("address"));
    JsonArray e = m.getJsonArray("expansions");
    assertEquals(2, e.size());
    assertEquals("new", e.get(0).asJsonObject().getJsonObject("builtFrom").getJsonArray("version").get(0).asString(),
        JsonParser.compose(m, false));
  }
}
