package org.hl7.fhir.igtools.ast;

import static org.hl7.fhir.igtools.ast.AstDeltaTest.commitAll;
import static org.hl7.fhir.igtools.ast.AstDeltaTest.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M3: the base AST is validated against its own revision before it is trusted. */
class BaseAstCheckTest {

  @TempDir
  Path ig;
  String rev;
  String digest;

  @BeforeEach
  void repo() throws Exception {
    git(ig, "init", "-q");
    Files.createDirectories(ig.resolve("input/fsh"));
    Files.writeString(ig.resolve("sushi-config.yaml"), "id: x\n");
    Files.writeString(ig.resolve("input/fsh/a.fsh"), "one");
    rev = commitAll(ig, "one");
    digest = InputDigest.of(ig);
  }

  static JsonObject manifest(String rev, String digest) {
    JsonObject inputs = new JsonObject();
    if (rev != null) {
      inputs.add("sourceRevision", rev);
    }
    if (digest != null) {
      inputs.add("inputDigest", digest);
    }
    JsonObject m = new JsonObject();
    m.add("inputs", inputs);
    return m;
  }

  @Test
  void aBaseBuiltFromItsRevisionIsTrustedEvenAfterHeadMoves() throws Exception {
    Files.writeString(ig.resolve("input/fsh/a.fsh"), "two");
    commitAll(ig, "two");
    Files.writeString(ig.resolve("input/fsh/dirty.fsh"), "uncommitted");
    assertNull(BaseAstCheck.mismatch(ig, manifest(rev, digest)));
    assertEquals("", Toolchain.exec(ig.toString(), "git", "worktree", "list", "--porcelain").out().lines()
        .filter(l -> l.startsWith("worktree ")).skip(1).reduce("", String::concat), "the temporary worktree is removed");
  }

  @Test
  void aDigestThatIsNotTheRevisionsForcesAFullBuild() {
    String r = BaseAstCheck.mismatch(ig, manifest(rev, "0".repeat(64)));
    assertNotNull(r);
    assertTrue(r.contains("inputDigest"), r);
  }

  @Test
  void aMissingSourceRevisionIsAReasonNotAnNpe() {
    assertNotNull(BaseAstCheck.mismatch(ig, manifest(null, digest)));
    assertNotNull(BaseAstCheck.mismatch(ig, new JsonObject()));
    assertNull(IncrementalBuildCli.sourceRevision(new JsonObject()));
  }

  @Test
  void anUnknownRevisionIsAReason() {
    assertNotNull(BaseAstCheck.mismatch(ig, manifest("0123456789abcdef0123456789abcdef01234567", digest)));
  }

  /**
   * fpmn, 2026-10-23: a previous build left untracked pages under input/, so
   * the base recorded a digest no checkout of its revision has. The tree is
   * reported when the AST is written, and the plan's reason names it.
   */
  @Test
  void aBaseBuiltOverAPreviousBuildsLeftoversSaysSo() throws Exception {
    assertEquals(java.util.List.of(), InputDigest.notAtRevision(ig));
    Files.createDirectories(ig.resolve("input/pagecontent"));
    Files.writeString(ig.resolve("input/pagecontent/ValueSet-X.md"), "placeholder");
    Files.writeString(ig.resolve("input/fsh/a.fsh"), "edited");
    assertEquals(java.util.List.of("input/fsh/a.fsh", "input/pagecontent/ValueSet-X.md"), InputDigest.notAtRevision(ig));
    String dirtyDigest = InputDigest.of(ig);
    JsonObject m = manifest(rev, dirtyDigest);
    m.add("inputsNotAtRevision", AstPublisher.notAtRevision(InputDigest.notAtRevision(ig)));
    String r = BaseAstCheck.mismatch(ig, m);
    assertNotNull(r);
    assertTrue(r.contains("2 input file(s) not at that revision, e.g. input/fsh/a.fsh"), r);
    assertNull(AstPublisher.notAtRevision(java.util.List.of()));
  }

  /** Option A (owner, 2026-10-23): an AST built with -verify-incremental is never a base. */
  @Test
  void aVerificationRunsOutputIsNeverABase() throws Exception {
    JsonObject m = manifest(rev, digest);
    assertNull(BaseAstCheck.mismatch(ig, m));
    Path mf = ig.resolve("manifest.json");
    Files.writeString(mf, org.hl7.fhir.utilities.json.parser.JsonParser.compose(m, true));
    IncrementalBuildCli.markUnverified(mf);
    String r = BaseAstCheck.mismatch(ig, org.hl7.fhir.utilities.json.parser.JsonParser.parseObject(Files.readString(mf)));
    assertNotNull(r);
    assertTrue(r.contains("unverified incremental"), r);
  }
}
