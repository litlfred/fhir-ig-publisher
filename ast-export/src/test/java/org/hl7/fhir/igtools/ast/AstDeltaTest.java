package org.hl7.fhir.igtools.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B1: a git failure is not an empty delta. */
class AstDeltaTest {

  @TempDir
  Path repo;

  static void git(Path dir, String... args) throws Exception {
    String[] cmd = new String[args.length + 5];
    cmd[0] = "git";
    cmd[1] = "-c";
    cmd[2] = "user.email=t@example.org";
    cmd[3] = "-c";
    cmd[4] = "user.name=t";
    System.arraycopy(args, 0, cmd, 5, args.length);
    Toolchain.Exec e = Toolchain.exec(dir.toString(), cmd);
    if (!e.ok()) {
      throw new AssertionError(String.join(" ", args) + ": " + e.err());
    }
  }

  static String commitAll(Path dir, String msg) throws Exception {
    git(dir, "add", "-A");
    git(dir, "commit", "-q", "--allow-empty", "-m", msg);
    return Toolchain.run(dir.toString(), "git", "rev-parse", "HEAD");
  }

  @Test
  void aMissingBaseRevisionIsAFailureNotAnEmptyDelta() throws Exception {
    git(repo, "init", "-q");
    Files.writeString(repo.resolve("a.txt"), "a");
    commitAll(repo, "one");
    FullBuildRequired e = assertThrows(FullBuildRequired.class,
        () -> AstDelta.fromGit(repo.toString(), "0123456789abcdef0123456789abcdef01234567", "HEAD"));
    assertTrue(e.getMessage().contains("git diff"), e.getMessage());
  }

  @Test
  void anEmptySuccessfulDiffIsAnEmptyList() throws Exception {
    git(repo, "init", "-q");
    Files.writeString(repo.resolve("a.txt"), "a");
    String rev = commitAll(repo, "one");
    assertEquals(List.of(), AstDelta.fromGit(repo.toString(), rev, "HEAD"));
  }

  @Test
  void pathsWithTabsAndRenamesSurviveTheNulFormat() throws Exception {
    git(repo, "init", "-q");
    Files.createDirectories(repo.resolve("input/fsh"));
    Files.writeString(repo.resolve("input/fsh/M.fsh"), "Instance: M\nInstanceOf: Measure\n* status = #draft\n".repeat(5));
    String base = commitAll(repo, "one");
    git(repo, "mv", "input/fsh/M.fsh", "input/fsh/Measure.fsh");
    Files.writeString(repo.resolve("input/fsh/odd\tname.fsh"), "x");
    commitAll(repo, "two");
    List<AstDelta.Change> d = AstDelta.fromGit(repo.toString(), base, "HEAD");
    assertTrue(d.contains(new AstDelta.Change('R', "input/fsh/Measure.fsh", "input/fsh/M.fsh")), d.toString());
    assertTrue(d.contains(new AstDelta.Change('A', "input/fsh/odd\tname.fsh", null)), d.toString());
  }

  @Test
  void aNoBaseRevisionIsAFailure() {
    assertThrows(FullBuildRequired.class, () -> AstDelta.fromGit(repo.toString(), null, "HEAD"));
  }
}
