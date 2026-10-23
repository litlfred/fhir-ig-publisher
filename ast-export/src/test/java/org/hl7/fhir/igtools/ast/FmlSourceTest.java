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
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A StructureMap whose source is a FHIR Mapping Language file in input/maps/,
 * not SUSHI output. smart-immunizations has 16 (bean qzex, 2026-10-23).
 */
class FmlSourceTest {

  static final String SM = "http://x/StructureMap/SM|1.0.0";

  @Test
  void aChangedFmlFileRebuildsItsStructureMapAndCopiesItIntoTheTemporaryIg(@TempDir Path tmp) throws Exception {
    Path ast = tmp.resolve("ast");
    List<FetchedFile> files = new ArrayList<>();
    FetchedFile f = new FetchedFile("input/maps/SM.fml");
    f.setRelativePath("input/maps/SM.fml");
    FetchedResource r = res("StructureMap", "SM",
        el("StructureMap", val("url", "http://x/StructureMap/SM"), val("version", "1.0.0"), val("name", "SM")));
    f.getResources().add(r);
    files.add(f);
    for (int i = 0; i < 10; i++) {
      FetchedFile p = new FetchedFile("fsh-generated/resources/Patient-p" + i + ".json");
      p.setRelativePath("fsh-generated/resources/Patient-p" + i + ".json");
      p.getResources().add(res("Patient", "p" + i, el("Patient")));
      files.add(p);
    }
    new AstExporter(x -> "{}".getBytes(StandardCharsets.UTF_8)).export(files, ast, new JsonObject());
    Files.writeString(ast.resolve("fsh-index.json"), "[]");

    JsonObject plan = new IncrementalPlan(ast, Map.of())
        .plan(AstDelta.parseNameStatus("M\tinput/maps/SM.fml"), 0.4);
    assertEquals("incremental", plan.asString("decision"));
    assertEquals(List.of(SM), IncrementalPlanTest.list(plan, "rebuild"));

    // The temporary IG gets the .fml itself; the Publisher finds it through the
    // IG's own path-resource parameter, which rewriteIg leaves alone.
    Path ig = tmp.resolve("ig");
    Files.createDirectories(ig.resolve("input/maps"));
    Files.createDirectories(ig.resolve("fsh-generated/resources"));
    Files.writeString(ig.resolve("input/maps/SM.fml"), "map \"http://x/StructureMap/SM\" = SM\n");
    Files.writeString(ig.resolve("ig.ini"), "[IG]\nig = fsh-generated/resources/ImplementationGuide-x.json\n");
    Files.writeString(ig.resolve("fsh-generated/resources/ImplementationGuide-x.json"), """
        {"resourceType": "ImplementationGuide", "id": "x",
         "definition": {"resource": [],
           "parameter": [{"code": {"code": "path-resource"}, "value": "input/maps"}]}}
        """);
    TempIgAssembler.Result a = TempIgAssembler.assemble(ig, tmp.resolve("work"), Set.of(SM),
        Map.of(SM, "input/maps/SM.fml"), Set.of(), "ast.cache#0.0.0");
    assertTrue(a.missing().isEmpty(), "nothing missing: " + a.missing());
    assertTrue(a.copied().contains("input/maps/SM.fml"));
    assertTrue(Files.exists(tmp.resolve("work/input/maps/SM.fml")));
    assertTrue(Files.readString(a.igResource()).contains("\"input/maps\""), "path-resource kept");
    // wv7r: a path-* directory exists in the temporary IG even with nothing copied into it.
    assertEquals(List.of("input/maps"), TempIgAssembler.pathParameterDirs(
        org.hl7.fhir.utilities.json.parser.JsonParser.parseObject(Files.readString(a.igResource()))));
    assertTrue(Files.isDirectory(tmp.resolve("work/input/maps")));
  }

  /** wv7r: Jekyll failed on the temporary IG because the template includes menu.xml, which SUSHI writes. */
  @Test
  void theTemporaryIgCarriesTheIncludesTheTemplateNeeds(@TempDir Path tmp) throws Exception {
    Path ig = tmp.resolve("ig");
    Files.createDirectories(ig.resolve("fsh-generated/resources"));
    Files.createDirectories(ig.resolve("fsh-generated/includes"));
    Files.createDirectories(ig.resolve("input/includes/sub"));
    Files.writeString(ig.resolve("fsh-generated/includes/menu.xml"), "<ul/>");
    Files.writeString(ig.resolve("input/includes/sub/note.md"), "x");
    Files.writeString(ig.resolve("ig.ini"), "[IG]\nig = fsh-generated/resources/ImplementationGuide-x.json\n");
    Files.writeString(ig.resolve("fsh-generated/resources/ImplementationGuide-x.json"),
        "{\"resourceType\": \"ImplementationGuide\", \"id\": \"x\", \"definition\": {\"resource\": [], "
        + "\"parameter\": [{\"code\": {\"code\": \"path-binary\"}, \"value\": \"input/cql\"}]}}");
    Files.createDirectories(ig.resolve("input/cql"));
    Path work = tmp.resolve("work");
    TempIgAssembler.assemble(ig, work, Set.of(), Map.of(), Set.of(), "ast.cache#0.0.0");
    assertEquals("<ul/>", Files.readString(work.resolve("fsh-generated/includes/menu.xml")));
    assertEquals("x", Files.readString(work.resolve("input/includes/sub/note.md")));
    assertTrue(Files.isDirectory(work.resolve("input/cql")), "the empty path-binary directory exists");
  }
}
