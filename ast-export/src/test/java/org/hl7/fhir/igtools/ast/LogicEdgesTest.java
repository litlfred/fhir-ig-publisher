package org.hl7.fhir.igtools.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.hl7.fhir.igtools.publisher.FetchedFile;
import org.hl7.fhir.igtools.publisher.FetchedResource;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogicEdgesTest {

  static Element el(String name, Element... children) {
    Element e = new Element(name);
    for (Element c : children) {
      e.getChildren().add(c);
    }
    return e;
  }

  static Element val(String name, String value) {
    return new Element(name, null, "string", value);
  }

  static FetchedResource res(String type, String id, Element root) {
    FetchedResource r = new FetchedResource(id);
    r.setType(type);
    r.setElement(root);
    r.setId(id);
    return r;
  }

  static final String X = "http://x/";

  /** Library A depends on Library B (pinned) and on a Library in another package; binds a ValueSet. */
  static Element libraryA() {
    return el("Library", val("url", X + "Library/A"), val("version", "1.0.0"),
        el("relatedArtifact", val("type", "depends-on"), val("resource", X + "Library/B|1.0.0")),
        el("relatedArtifact", val("type", "depends-on"), val("resource", "http://other/Library/FHIRHelpers|4.0.1")),
        el("relatedArtifact", val("type", "documentation"), val("resource", X + "Library/Ignored")),
        el("dataRequirement", el("codeFilter", val("valueSet", X + "ValueSet/VS1"))));
  }

  /** PlanDefinition uses A, and a nested action points at an ActivityDefinition. */
  static Element planDefinition() {
    return el("PlanDefinition", val("url", X + "PlanDefinition/PD"), val("version", "1.0.0"),
        val("library", X + "Library/A"),
        el("action", el("action", val("definitionCanonical", X + "ActivityDefinition/AD"))));
  }

  @Test
  void extractsLogicEdgesAndIgnoresNonDependencyArtifacts() {
    List<LogicEdges.Edge> a = LogicEdges.of("Library", libraryA());
    assertEquals(3, a.size(), "two depends-on + one codeFilter valueSet; documentation is not a dependency");
    assertEquals(new LogicEdges.Edge("relatedArtifact.depends-on", X + "Library/B", "1.0.0",
        "Library.relatedArtifact.resource"), a.get(0));
    assertEquals("dataRequirement.valueSet", a.get(2).kind());

    List<LogicEdges.Edge> pd = LogicEdges.of("PlanDefinition", planDefinition());
    assertEquals(List.of("library", "action.definition"), pd.stream().map(LogicEdges.Edge::kind).toList());
    assertEquals("PlanDefinition.action.action.definitionCanonical", pd.get(1).path(), "nested actions are walked");
  }

  @Test
  void aNonLogicResourceHasNoLogicEdges() {
    assertTrue(LogicEdges.of("ValueSet", el("ValueSet", val("library", "x"))).isEmpty());
  }

  @Test
  void dependenciesResolveInsideTheIgAndKeepExternalTargetsAsUnresolved(@TempDir Path out) throws Exception {
    FetchedFile f = new FetchedFile("input/fsh/logic.fsh");
    f.getResources().add(res("Library", "A", libraryA()));
    f.getResources().add(res("Library", "B", el("Library", val("url", X + "Library/B"), val("version", "1.0.0"))));
    f.getResources().add(res("PlanDefinition", "PD", planDefinition()));
    f.getResources().add(res("Measure", "M", el("Measure", val("url", X + "Measure/M"), val("library", X + "Library/A"))));

    new AstExporter(r -> "{}".getBytes(StandardCharsets.UTF_8)).export(List.of(f), out, new JsonObject());

    JsonObject d = JsonParser.parseObject(Files.readString(out.resolve("dependencies.json")));
    assertEquals("cache", d.asString("authority"));
    JsonArray rows = d.getJsonArray("dependencies");
    assertEquals(6, rows.size(), "A:3, PD:2, M:1");
    assertEquals(3, d.asInteger("resolvedInIg"), "A->B, PD->A, M->A");

    JsonObject external = find(rows, "http://other/Library/FHIRHelpers");
    assertTrue(external.get("resolved").isJsonNull(), "another package's library is kept, marked unresolved");
    assertEquals("4.0.1", external.asString("targetVersion"));
    assertEquals(X + "Library/A|1.0.0", find(rows, X + "Library/B").asString("source"));
    assertEquals(X + "Library/B|1.0.0", find(rows, X + "Library/B").asString("resolved"));
  }

  static JsonObject find(JsonArray rows, String target) {
    for (int i = 0; i < rows.size(); i++) {
      JsonObject o = rows.get(i).asJsonObject();
      if (target.equals(o.asString("target"))) {
        return o;
      }
    }
    throw new AssertionError("no edge to " + target);
  }
}
