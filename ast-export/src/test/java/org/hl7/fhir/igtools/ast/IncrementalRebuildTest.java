package org.hl7.fhir.igtools.ast;

import static org.hl7.fhir.igtools.ast.IncrementalPlanTest.A;
import static org.hl7.fhir.igtools.ast.IncrementalPlanTest.B;
import static org.hl7.fhir.igtools.ast.IncrementalPlanTest.M;
import static org.hl7.fhir.igtools.ast.IncrementalPlanTest.PD;
import static org.hl7.fhir.igtools.ast.IncrementalPlanTest.X;
import static org.hl7.fhir.igtools.ast.LogicEdgesTest.el;
import static org.hl7.fhir.igtools.ast.LogicEdgesTest.val;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.igtools.publisher.FetchedFile;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** W7's pieces, each alone. The loop that joins them needs a real Publisher run. */
class IncrementalRebuildTest {

  @TempDir
  Path tmp;

  Path baseAst() throws Exception {
    IncrementalPlanTest t = new IncrementalPlanTest();
    t.ast = tmp.resolve("base");
    t.base();
    return t.ast;
  }

  @Test
  void theCachedPartIsWrittenAsAPackageInTheCacheLayout() throws Exception {
    Path base = baseAst();
    Path cache = tmp.resolve("cache");
    Path dir = CachePackageWriter.write(base, Set.of(A, B), cache, "x.ig.ast-cache", "0.0.0-ast.abc.r1", "4.0.1");

    assertEquals(cache.resolve("x.ig.ast-cache#0.0.0-ast.abc.r1"), dir);
    Path pkg = dir.resolve("package");
    assertTrue(Files.exists(pkg.resolve("Library-A.json")));
    assertFalse(Files.exists(pkg.resolve("PlanDefinition-PD.json")), "only the keys asked for");
    JsonObject pj = JsonParser.parseObject(Files.readString(pkg.resolve("package.json")));
    assertEquals("x.ig.ast-cache", pj.asString("name"));
    assertEquals("4.0.1", pj.getJsonArray("fhirVersions").get(0).asString());
    JsonArray files = JsonParser.parseObject(Files.readString(pkg.resolve(".index.json"))).getJsonArray("files");
    assertEquals(2, files.size());
    assertEquals(X + "Library/A", files.get(0).asJsonObject().asString("url"));
  }

  @Test
  void theTemporaryIgListsOnlyTheRebuildSetAndDependsOnTheCache() throws Exception {
    Path ig = tmp.resolve("ig");
    Files.createDirectories(ig.resolve("fsh-generated/resources"));
    Files.createDirectories(ig.resolve("input/cql"));
    Files.writeString(ig.resolve("ig.ini"), "[IG]\nig = fsh-generated/resources/ImplementationGuide-x.ig.json\ntemplate = fhir.base.template#current\n");
    Files.writeString(ig.resolve("fsh-generated/resources/ImplementationGuide-x.ig.json"), """
        {"resourceType": "ImplementationGuide", "packageId": "x.ig", "fhirVersion": ["4.0.1"],
         "dependsOn": [{"packageId": "hl7.fhir.uv.extensions.r4", "version": "5.2.0"}],
         "definition": {
           "resource": [
             {"reference": {"reference": "Library/A"}},
             {"reference": {"reference": "PlanDefinition/PD"}},
             {"reference": {"reference": "Patient/p0"}}
           ],
           "page": {"nameUrl": "toc.html", "title": "TOC", "generation": "html",
                    "page": [{"nameUrl": "index.html", "title": "Home", "generation": "markdown"}]}
         }}
        """);
    Files.writeString(ig.resolve("fsh-generated/resources/Library-A.json"), "{}");
    Files.writeString(ig.resolve("fsh-generated/resources/PlanDefinition-PD.json"), "{}");
    Files.writeString(ig.resolve("input/cql/A.cql"), "library A");

    TempIgAssembler.Result r = TempIgAssembler.assemble(ig, tmp.resolve("work"), Set.of(A, PD, M),
        Map.of(A, "fsh-generated/resources/Library-A.json", PD, "fsh-generated/resources/PlanDefinition-PD.json"),
        Set.of("A"), "x.ig.ast-cache#0.0.0-ast.abc.r1");

    assertEquals(List.of(M), r.missing(), "a key with no source is reported, not dropped silently");
    assertTrue(r.copied().contains("input/cql/A.cql"), "a rebuilt Library carries its CQL");
    JsonObject out = JsonParser.parseObject(Files.readString(r.igResource()));
    JsonArray res = out.getJsonObject("definition").getJsonArray("resource");
    assertEquals(2, res.size(), "Patient/p0 is not in the rebuild set");
    JsonArray deps = out.getJsonArray("dependsOn");
    assertEquals(2, deps.size(), "the IG's own dependencies are kept");
    assertEquals("x.ig.ast-cache", deps.get(1).asJsonObject().asString("packageId"));
    assertEquals("0.0.0-ast.abc.r1", deps.get(1).asJsonObject().asString("version"));
    assertFalse(out.getJsonObject("definition").getJsonObject("page").has("page"), "pages cut to one stub");
    assertTrue(Files.exists(tmp.resolve("work/ig.ini")));
  }

  @Test
  void mergeReplacesRebuiltResourcesReResolvesEdgesAndReportsGrowth() throws Exception {
    Path base = baseAst();
    // The partial rebuild holds A and B only. PD and M depend on A in the
    // base and were not rebuilt, so the merged graph's cone must name them.
    FetchedFile f = new FetchedFile("fsh-generated/resources/Library-B.json");
    f.setRelativePath("fsh-generated/resources/Library-B.json");
    f.getResources().add(LogicEdgesTest.res("Library", "B",
        el("Library", val("url", X + "Library/B"), val("version", "1.0.0"), val("name", "B"))));
    FetchedFile g = new FetchedFile("fsh-generated/resources/Library-A.json");
    g.setRelativePath("fsh-generated/resources/Library-A.json");
    g.getResources().add(LogicEdgesTest.res("Library", "A",
        el("Library", val("url", X + "Library/A"), val("version", "1.0.0"), val("name", "A"),
            el("relatedArtifact", val("type", "depends-on"), val("resource", X + "Library/B|1.0.0")))));
    Path partial = tmp.resolve("partial");
    new AstExporter(r -> "{\"rebuilt\":true}".getBytes(StandardCharsets.UTF_8)).export(List.of(f, g), partial,
        new JsonObject());

    AstMerger.Result r = AstMerger.merge(base, partial, Set.of(A, B), Set.of(), "base-rev", "head-rev", null,
        tmp.resolve("merged"));

    assertEquals(14, r.resources(), "same count: two replaced, twelve kept");
    assertEquals(Set.of(PD, M), r.grew(), "PD and M depend on rebuilt A and were not rebuilt: another round");
    Path merged = tmp.resolve("merged");
    JsonObject m = JsonParser.parseObject(Files.readString(merged.resolve("manifest.json")));
    assertEquals("{\"rebuilt\":true}", Files.readString(merged.resolve(find(m, A).asString("file"))));
    assertTrue(m.asBoolean("mixed"));
    assertEquals("head-rev", find(m, A).asString("builtAt"));
    assertEquals("base-rev", find(m, PD).asString("builtAt"));
    JsonObject d = JsonParser.parseObject(Files.readString(merged.resolve("dependencies.json")));
    assertEquals(3, d.asInteger("resolvedInIg"), "A->B from the partial, PD->A and M->A from the base, all land");
  }

  @Test
  void mergeThatRebuiltTheWholeConeDoesNotGrow() throws Exception {
    Path base = baseAst();
    Path partial = tmp.resolve("partial");
    FetchedFile f = new FetchedFile("fsh-generated/resources/PlanDefinition-PD.json");
    f.setRelativePath("fsh-generated/resources/PlanDefinition-PD.json");
    f.getResources().add(LogicEdgesTest.res("PlanDefinition", "PD", el("PlanDefinition",
        val("url", X + "PlanDefinition/PD"), val("version", "1.0.0"), val("library", X + "Library/A"))));
    new AstExporter(r -> "{}".getBytes(StandardCharsets.UTF_8)).export(List.of(f), partial, new JsonObject());

    AstMerger.Result r = AstMerger.merge(base, partial, Set.of(PD), Set.of(), "b", "h", null, tmp.resolve("m"));
    assertTrue(r.grew().isEmpty(), "a leaf has no dependents: converged in one round");
  }

  static JsonObject find(JsonObject manifest, String key) {
    JsonArray a = manifest.getJsonArray("resources");
    for (int i = 0; i < a.size(); i++) {
      if (key.equals(a.get(i).asJsonObject().asString("key"))) {
        return a.get(i).asJsonObject();
      }
    }
    throw new AssertionError(key);
  }

  // B2: SUSHI writes fsh-generated/data/fsh-index.json, and a new non-FSH source must be carried too.

  Path igWithNewSources() throws Exception {
    Path ig = tmp.resolve("ig");
    Files.createDirectories(ig.resolve("fsh-generated/data"));
    Files.createDirectories(ig.resolve("fsh-generated/resources"));
    Files.createDirectories(ig.resolve("input/resources"));
    Files.writeString(ig.resolve("fsh-generated/data/fsh-index.json"), """
        [{"outputFile": "Library-N.json", "fshName": "N", "fshType": "Instance", "fshFile": "logic/N.fsh"}]
        """);
    Files.writeString(ig.resolve("fsh-generated/resources/Library-N.json"), "{}");
    Files.writeString(ig.resolve("input/resources/Library-J.json"), "{}");
    return ig;
  }

  @Test
  void aNewFshFileIsFoundThroughTheIndexSushiActuallyWrites() throws Exception {
    Path ig = igWithNewSources();
    Path round = tmp.resolve("r1");
    IncrementalBuildCli.copyNewSourceOutputs(ig, round, "input/fsh/logic/N.fsh");
    assertTrue(Files.exists(round.resolve("fsh-generated/resources/Library-N.json")));
  }

  @Test
  void aNewNonFshResourceFileIsCopiedItself() throws Exception {
    Path ig = igWithNewSources();
    Path round = tmp.resolve("r1");
    IncrementalBuildCli.copyNewSourceOutputs(ig, round, "input/resources/Library-J.json");
    assertTrue(Files.exists(round.resolve("input/resources/Library-J.json")));
  }

  @Test
  void aNewSourceThatYieldsNoResourceForcesAFullBuild() throws Exception {
    Path ig = igWithNewSources();
    Path round = tmp.resolve("r1");
    assertThrows(FullBuildRequired.class,
        () -> IncrementalBuildCli.copyNewSourceOutputs(ig, round, "input/fsh/logic/Unindexed.fsh"));
    assertThrows(FullBuildRequired.class,
        () -> IncrementalBuildCli.copyNewSourceOutputs(ig, round, "input/cql/New.cql"));
    Files.delete(ig.resolve("fsh-generated/data/fsh-index.json"));
    assertThrows(FullBuildRequired.class,
        () -> IncrementalBuildCli.copyNewSourceOutputs(ig, round, "input/fsh/logic/N.fsh"));
  }

  @Test
  void newSourceFilesAreListedInTheTemporaryIg() throws Exception {
    Path ig = tmp.resolve("ig2");
    Files.createDirectories(ig.resolve("fsh-generated/resources"));
    Files.writeString(ig.resolve("ig.ini"), "[IG]\nig = fsh-generated/resources/ImplementationGuide-x.ig.json\n");
    Files.writeString(ig.resolve("fsh-generated/resources/ImplementationGuide-x.ig.json"), """
        {"resourceType": "ImplementationGuide", "definition": {"resource": [
          {"reference": {"reference": "Library/N"}}, {"reference": {"reference": "Patient/p0"}}]}}
        """);
    Files.writeString(ig.resolve("fsh-generated/resources/Library-N.json"), "{}");
    TempIgAssembler.Result r = TempIgAssembler.assemble(ig, tmp.resolve("w"), Set.of(), Map.of(), Set.of(),
        "x.ig.ast-cache#0.0.0-ast.abc.r1", List.of("fsh-generated/resources/Library-N.json"));
    JsonArray res = JsonParser.parseObject(Files.readString(r.igResource())).getJsonObject("definition")
        .getJsonArray("resource");
    assertEquals(1, res.size());
  }
}
