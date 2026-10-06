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
}
