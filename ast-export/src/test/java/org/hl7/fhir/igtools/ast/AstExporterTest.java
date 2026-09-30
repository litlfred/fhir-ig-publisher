package org.hl7.fhir.igtools.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.hl7.fhir.igtools.publisher.FetchedFile;
import org.hl7.fhir.igtools.publisher.FetchedResource;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AstExporterTest {

  static FetchedResource resource(String type, String id, String url, String version) {
    Element e = new Element(type);
    if (url != null) {
      e.getChildren().add(new Element("url", null, "uri", url));
    }
    if (version != null) {
      e.getChildren().add(new Element("version", null, "string", version));
    }
    FetchedResource r = new FetchedResource(id);
    // A bare Element has no Property, so its fhirType() cannot be asked;
    // set the type first, as the Publisher's loaders do.
    r.setType(type);
    r.setElement(e);
    r.setId(id);
    return r;
  }

  static FetchedFile file(String path, FetchedResource... rs) {
    FetchedFile f = new FetchedFile(path);
    for (FetchedResource r : rs) {
      f.getResources().add(r);
    }
    return f;
  }

  @Test
  void keyIsCanonicalPipeVersionElseTypeSlashId() {
    assertEquals("http://x/Library/A|1.0.0", AstExporter.keyOf("Library", "A", "http://x/Library/A", "1.0.0"));
    assertEquals("http://x/Library/A", AstExporter.keyOf("Library", "A", "http://x/Library/A", null));
    assertEquals("Patient/p1", AstExporter.keyOf("Patient", "p1", null, null));
  }

  @Test
  void writesOneFilePerResourceAndAManifestThatSaysItIsACache(@TempDir Path out) throws Exception {
    List<FetchedFile> files = List.of(
        file("input/fsh/lib.fsh",
            resource("Library", "LibB", "http://x/Library/LibB", "0.2.0"),
            resource("Library", "LibA", "http://x/Library/LibA", "0.2.0")),
        file("input/examples/p1.json", resource("Patient", "p1", null, null)));
    AstExporter exporter = new AstExporter(r -> ("{\"id\":\"" + r.getId() + "\"}").getBytes(StandardCharsets.UTF_8));
    JsonObject header = new JsonObject();
    header.add("ig", new JsonObject().add("version", "0.2.0"));

    List<AstExporter.Entry> entries = exporter.export(files, out, header);

    assertEquals(3, entries.size());
    assertEquals("Patient/p1", entries.get(0).key());
    assertEquals("http://x/Library/LibA|0.2.0", entries.get(1).key());
    assertTrue(Files.exists(out.resolve("resources/Library/LibA.json")));
    assertEquals("{\"id\":\"p1\"}", Files.readString(out.resolve("resources/Patient/p1.json")));

    JsonObject m = JsonParser.parseObject(Files.readString(out.resolve("manifest.json")));
    assertEquals(AstExporter.SCHEMA, m.asString("$schema"));
    assertEquals("cache", m.asString("authority"));
    assertEquals(3, m.getJsonArray("provisional").size());
    assertEquals("0.2.0", m.getJsonObject("ig").asString("version"));
    JsonObject lib = m.getJsonArray("resources").get(1).asJsonObject();
    assertEquals("input/fsh/lib.fsh", lib.asString("source"));
    assertTrue(m.getJsonArray("resources").get(0).asJsonObject().get("canonical").isJsonNull());
  }

  @Test
  void inputDigestFollowsInputsAndIgnoresEverythingElse(@TempDir Path ig) throws Exception {
    Files.writeString(ig.resolve("sushi-config.yaml"), "id: x\n");
    Files.createDirectories(ig.resolve("input/fsh"));
    Files.writeString(ig.resolve("input/fsh/a.fsh"), "Profile: A\n");
    String d0 = InputDigest.of(ig);
    assertEquals(d0, InputDigest.of(ig), "deterministic");

    Files.createDirectories(ig.resolve("output"));
    Files.writeString(ig.resolve("output/index.html"), "<html/>");
    assertEquals(d0, InputDigest.of(ig), "build output is not an input");

    Files.move(ig.resolve("input/fsh/a.fsh"), ig.resolve("input/fsh/b.fsh"));
    assertNotEquals(d0, InputDigest.of(ig), "a rename changes the digest");
  }

  /**
   * The golden vector folio-assistant's TypeScript twin
   * ({@code fhir-harness/scripts/ig-ast.ts}, {@code inputDigest}) asserts too.
   * Change the algorithm here and that test fails, which is the point: the
   * writer and the staleness checker are in different languages.
   */
  @Test
  void inputDigestGoldenVectorSharedWithFolioAssistant(@TempDir Path ig) throws Exception {
    Files.createDirectories(ig.resolve("input/fsh"));
    Files.createDirectories(ig.resolve("input/pagecontent"));
    Files.createDirectories(ig.resolve("output"));
    Files.writeString(ig.resolve("sushi-config.yaml"), "id: x\n");
    Files.writeString(ig.resolve("ig.ini"), "[IG]\nig = fsh-generated/resources/ImplementationGuide-x.json\n");
    Files.writeString(ig.resolve("input/fsh/a.fsh"), "Profile: A\n");
    Files.writeString(ig.resolve("input/pagecontent/index.md"), "# Hi\n");
    Files.writeString(ig.resolve("output/x.html"), "noise");
    assertEquals("58871352384745e1d7fd68f7ea918b0e2febbd86cf36a9cb5f0c7ce9d13a82f1", InputDigest.of(ig));
  }
}
