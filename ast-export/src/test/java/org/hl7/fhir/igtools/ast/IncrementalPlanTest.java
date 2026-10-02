package org.hl7.fhir.igtools.ast;

import static org.hl7.fhir.igtools.ast.LogicEdgesTest.el;
import static org.hl7.fhir.igtools.ast.LogicEdgesTest.res;
import static org.hl7.fhir.igtools.ast.LogicEdgesTest.val;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.igtools.publisher.FetchedFile;
import org.hl7.fhir.igtools.publisher.FetchedResource;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A base AST of: Library B <- Library A <- {PlanDefinition PD, Measure M},
 * plus ten unrelated examples, built from FSH through SUSHI's fsh-index.
 */
class IncrementalPlanTest {

  static final String X = "http://x/";
  static final String A = X + "Library/A|1.0.0";
  static final String B = X + "Library/B|1.0.0";
  static final String PD = X + "PlanDefinition/PD|1.0.0";
  static final String M = X + "Measure/M|1.0.0";

  @TempDir
  Path ast;


  static FetchedResource fromFsh(String type, String id, Element root) {
    FetchedFile f = new FetchedFile("fsh-generated/resources/" + type + "-" + id + ".json");
    f.setRelativePath("fsh-generated/resources/" + type + "-" + id + ".json");
    FetchedResource r = res(type, id, root);
    f.getResources().add(r);
    FILES.add(f);
    return r;
  }

  static final List<FetchedFile> FILES = new ArrayList<>();

  @BeforeEach
  void base() throws Exception {
    FILES.clear();
    fromFsh("Library", "A", el("Library", val("url", X + "Library/A"), val("version", "1.0.0"), val("name", "A"),
        el("relatedArtifact", val("type", "depends-on"), val("resource", X + "Library/B|1.0.0"))));
    fromFsh("Library", "B", el("Library", val("url", X + "Library/B"), val("version", "1.0.0"), val("name", "B")));
    fromFsh("PlanDefinition", "PD", el("PlanDefinition", val("url", X + "PlanDefinition/PD"), val("version", "1.0.0"),
        val("library", X + "Library/A")));
    fromFsh("Measure", "M", el("Measure", val("url", X + "Measure/M"), val("version", "1.0.0"),
        val("library", X + "Library/A")));
    for (int i = 0; i < 10; i++) {
      fromFsh("Patient", "p" + i, el("Patient"));
    }
    new AstExporter(r -> "{}".getBytes(StandardCharsets.UTF_8)).export(FILES, ast, new JsonObject());
    Files.writeString(ast.resolve("fsh-index.json"), """
        [
          {"outputFile": "Library-A.json", "fshName": "A", "fshType": "Instance", "fshFile": "logic/A.fsh"},
          {"outputFile": "Library-B.json", "fshName": "B", "fshType": "Instance", "fshFile": "logic/B.fsh"},
          {"outputFile": "PlanDefinition-PD.json", "fshName": "PD", "fshType": "Instance", "fshFile": "logic/PD.fsh"},
          {"outputFile": "Measure-M.json", "fshName": "M", "fshType": "Instance", "fshFile": "logic/M.fsh"},
          {"outputFile": "Patient-p0.json", "fshName": "p0", "fshType": "Instance", "fshFile": "examples.fsh"}
        ]
        """);
  }

  JsonObject plan(String nameStatus, Map<String, Set<String>> fshUsers, double threshold) throws Exception {
    return new IncrementalPlan(ast, fshUsers).plan(AstDelta.parseNameStatus(nameStatus), threshold);
  }

  static List<String> list(JsonObject p, String name) {
    JsonArray a = p.getJsonArray(name);
    List<String> out = new ArrayList<>();
    for (int i = 0; i < a.size(); i++) {
      out.add(a.get(i).asString());
    }
    return out;
  }

  @Test
  void editingALibraryRebuildsEverythingThatDependsOnItAndLoadsNothingElse() throws Exception {
    JsonObject p = plan("M\tinput/fsh/logic/B.fsh", null, 0.4);
    assertEquals("incremental", p.asString("decision"));
    assertEquals(List.of(B), list(p, "seeds"));
    assertEquals(Set.of(A, B, PD, M), Set.copyOf(list(p, "rebuild")), "forward cone, transitively");
    assertTrue(list(p, "loadFromCache").isEmpty());
    assertTrue(p.asString("provisional").contains("fixed point") || p.asString("provisional").contains("stops growing"));
  }

  @Test
  void editingALeafRebuildsItAloneAndLoadsWhatItUses() throws Exception {
    JsonObject p = plan("M\tinput/fsh/logic/PD.fsh", null, 0.4);
    assertEquals(List.of(PD), list(p, "rebuild"));
    assertEquals(Set.of(A, B), Set.copyOf(list(p, "loadFromCache")), "its dependencies come from the cache");
  }

  @Test
  void aCqlFileMapsToTheLibraryOfTheSameName() throws Exception {
    JsonObject p = plan("M\tinput/cql/A.cql", null, 0.4);
    assertEquals(List.of(A), list(p, "seeds"));
  }

  @Test
  void aPageChangeRebuildsNoResource() throws Exception {
    JsonObject p = plan("M\tinput/pagecontent/index.md", null, 0.4);
    assertEquals("incremental", p.asString("decision"));
    assertTrue(list(p, "rebuild").isEmpty());
  }

  @Test
  void configurationAndUnknownFilesForceAFullBuildWithTheReason() throws Exception {
    JsonObject p = plan("M\tsushi-config.yaml\nM\tinput/something/odd.xml", null, 0.4);
    assertEquals("full", p.asString("decision"));
    List<String> why = list(p, "fullBuildBecause");
    assertTrue(why.get(0).contains("configuration"));
    assertTrue(why.get(1).contains("cannot tell"), "cannot tell is never incremental");
  }

  @Test
  void aRuleSetFileIsAFullBuildUnlessFshConeSuppliesItsUsers() throws Exception {
    assertEquals("full", plan("M\tinput/fsh/rulesets.fsh", null, 0.4).asString("decision"));
    JsonObject p = plan("M\tinput/fsh/rulesets.fsh", Map.of("input/fsh/rulesets.fsh", Set.of("input/fsh/logic/PD.fsh")), 0.4);
    assertEquals("incremental", p.asString("decision"));
    assertEquals(List.of(PD), list(p, "seeds"));
  }

  @Test
  void deletingAFileRemovesItsResourceAndRebuildsItsDependents() throws Exception {
    JsonObject p = plan("D\tinput/fsh/logic/A.fsh", null, 0.4);
    assertEquals(List.of(A), list(p, "remove"));
    assertEquals(Set.of(PD, M), Set.copyOf(list(p, "rebuild")));
  }

  @Test
  void aRenameIsADeleteAndANewSource() throws Exception {
    JsonObject p = plan("R100\tinput/fsh/logic/M.fsh\tinput/fsh/logic/Measure.fsh", null, 0.4);
    assertEquals(List.of(M), list(p, "remove"));
    JsonArray files = p.getJsonArray("files");
    assertEquals("NEW_SOURCE", files.get(1).asJsonObject().asString("effect"));
  }

  @Test
  void aConeAboveTheThresholdIsAFullBuild() throws Exception {
    JsonObject p = plan("M\tinput/fsh/logic/B.fsh", null, 0.2);
    assertEquals("full", p.asString("decision"), "4 of 14 resources is 28.6%, above 20%");
    assertTrue(list(p, "fullBuildBecause").get(0).contains("threshold"));
  }

  @Test
  void fshConeFileUsersAreReadInTheFormatFolioAssistantWrites() throws Exception {
    Path f = ast.resolve("users.json");
    Files.writeString(f, """
        {
          "$schema": "fsh-file-users/v1",
          "root": "/tmp/ig",
          "users": {
            "input/fsh/rulesets.fsh": ["input/fsh/logic/PD.fsh"]
          }
        }
        """);
    JsonObject p = new IncrementalPlan(ast, IncrementalPlan.readFshUsers(f))
        .plan(AstDelta.parseNameStatus("M\tinput/fsh/rulesets.fsh"), 0.4);
    assertEquals("incremental", p.asString("decision"));
    assertEquals(List.of(PD), list(p, "seeds"));
  }

  @Test
  void theGuardDecidesFullAndKeepsTheComputedPlanForReview() throws Exception {
    JsonObject p = IncrementalPlan.guard(plan("M\tinput/fsh/logic/PD.fsh", null, 0.4), true);
    assertEquals("full", p.asString("decision"));
    assertEquals("incremental", p.asString("computedDecision"));
    assertTrue(list(p, "fullBuildBecause").contains(IncrementalPlan.GUARD_REASON));
    assertEquals(List.of(PD), list(p, "rebuild"), "the plan itself is still written");
  }

  @Test
  void theGuardIsOn() throws Exception {
    // Lifted only by the owner, after review: see IncrementalPlan.INCREMENTAL_GUARD.
    assertTrue(IncrementalPlan.INCREMENTAL_GUARD);
    assertEquals("full", IncrementalPlan.guard(plan("M\tinput/fsh/logic/PD.fsh", null, 0.4)).asString("decision"));
  }
}
