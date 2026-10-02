package org.hl7.fhir.igtools.ast;

import static org.hl7.fhir.igtools.ast.AstDeltaTest.commitAll;
import static org.hl7.fhir.igtools.ast.AstDeltaTest.git;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M2: the work tree the build reads must be the revision the plan diffs. */
class WorkTreeCheckTest {

  @TempDir
  Path ig;
  String first;
  String second;

  @BeforeEach
  void repo() throws Exception {
    git(ig, "init", "-q");
    Files.writeString(ig.resolve("a.fsh"), "one");
    first = commitAll(ig, "one");
    Files.writeString(ig.resolve("a.fsh"), "two");
    second = commitAll(ig, "two");
  }

  @Test
  void aCleanTreeAtHeadIsAccepted() {
    assertNull(IncrementalBuildCli.workTreeMismatch(ig, "HEAD"));
    assertNull(IncrementalBuildCli.workTreeMismatch(ig, second));
  }

  @Test
  void aDirtyTreeIsRefused() throws Exception {
    Files.writeString(ig.resolve("a.fsh"), "edited");
    assertNotNull(IncrementalBuildCli.workTreeMismatch(ig, "HEAD"));
  }

  @Test
  void anUntrackedFileIsRefused() throws Exception {
    Files.writeString(ig.resolve("new.fsh"), "x");
    assertNotNull(IncrementalBuildCli.workTreeMismatch(ig, "HEAD"));
  }

  @Test
  void aHeadOtherThanTheCheckedOutCommitIsRefused() {
    assertNotNull(IncrementalBuildCli.workTreeMismatch(ig, first));
  }

  @Test
  void stagedNeedsNoUnstagedEdits() throws Exception {
    Files.writeString(ig.resolve("a.fsh"), "staged");
    git(ig, "add", "a.fsh");
    assertNull(IncrementalBuildCli.workTreeMismatch(ig, null));
    Files.writeString(ig.resolve("a.fsh"), "unstaged on top");
    assertNotNull(IncrementalBuildCli.workTreeMismatch(ig, null));
  }
}
