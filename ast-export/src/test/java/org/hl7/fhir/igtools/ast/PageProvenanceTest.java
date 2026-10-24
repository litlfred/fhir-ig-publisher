package org.hl7.fhir.igtools.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** W4 at fragment level (owner, 2026-10-24, option A). */
class PageProvenanceTest {

  @TempDir
  Path root;

  private Path write(Path p, String s) throws Exception {
    Files.createDirectories(p.getParent());
    Files.writeString(p, s);
    return p;
  }

  @Test
  void aPageReachesTheSourceOfEveryFragmentItIncludes() throws Exception {
    Path temp = root.resolve("temp/pages");
    Path inc = temp.resolve("_includes");
    Path summary = write(inc.resolve("Library-A-summary.xhtml"), "<p>A</p>{% include Library-A-inner.xhtml %}");
    Path inner = write(inc.resolve("Library-A-inner.xhtml"), "<p>inner</p>");
    Path libPage = write(temp.resolve("Library-A.html"), "---\n---\n{% include Library-A-summary.xhtml %}");
    write(temp.resolve("overview.md"), "Intro {% include Library-A-summary.xhtml %} and {% include {{[type]}}-x.xhtml %}");
    Path src = write(root.resolve("input/cql/A.cql"), "library A");

    JsonObject o = PageProvenance.build(root, temp, List.of(new PageProvenance.Source(src.toString(),
        List.of("http://x/Library/A|1.0"), List.of(summary.toString(), inner.toString(), libPage.toString(),
            root.resolve("output/qa/x.html").toString()))));

    JsonObject frag = o.getJsonObject("fragments");
    assertEquals("input/cql/A.cql", frag.getJsonObject("Library-A-summary.xhtml").asString("source"));
    assertEquals(2, frag.getNames().size());
    JsonObject pages = o.getJsonObject("pages");
    assertEquals("input/cql/A.cql", pages.getJsonObject("Library-A.html").asString("source"));
    assertEquals(2, pages.getJsonObject("Library-A.html").getJsonArray("fragments").size(), "followed through the nested include");
    JsonObject overview = pages.getJsonObject("overview.md");
    assertFalse(overview.has("source"), "a narrative page no source wrote");
    assertEquals(2, overview.getJsonArray("fragments").size());
    assertEquals(1, o.asInteger("unresolvedIncludes"), "a variable include is counted, not guessed");
    assertFalse(pages.has("x.html"), "outputs outside the temp folder are not pages");
  }

  @Test
  void anIncrementalRoundReplacesOnlyWhatItRebuilt() throws Exception {
    JsonObject base = new JsonObject()
        .add("fragments", new JsonObject()
            .add("Library-A-summary.xhtml", new JsonObject().add("source", "input/cql/A.cql"))
            .add("Library-A-old.xhtml", new JsonObject().add("source", "input/cql/A.cql"))
            .add("Library-B-summary.xhtml", new JsonObject().add("source", "input/cql/B.cql")))
        .add("pages", new JsonObject()
            .add("index.html", new JsonObject().add("fragments", new org.hl7.fhir.utilities.json.model.JsonArray()))
            .add("Library-B.html", new JsonObject().add("source", "input/cql/B.cql")))
        .add("unresolvedIncludes", 3);
    JsonObject part = new JsonObject()
        .add("fragments", new JsonObject()
            .add("Library-A-summary.xhtml", new JsonObject().add("source", "input/cql/A.cql").add("new", true)))
        .add("pages", new JsonObject()
            .add("index.html", new JsonObject().add("stub", true)));
    JsonObject m = PageProvenance.merge(base, part);
    JsonObject f = m.getJsonObject("fragments");
    assertTrue(f.getJsonObject("Library-A-summary.xhtml").has("new"));
    assertFalse(f.has("Library-A-old.xhtml"), "a rebuilt source's fragments are the round's alone");
    assertTrue(f.has("Library-B-summary.xhtml"));
    assertFalse(m.getJsonObject("pages").getJsonObject("index.html").has("stub"), "the round's stub page is not the site's");
    assertEquals(3, m.asInteger("unresolvedIncludes"));
  }
}
