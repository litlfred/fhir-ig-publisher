package org.hl7.fhir.igtools.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * A staged set of changed files — a commit range, a PR's diff, or the git
 * index — reduced to one shape: added, modified, deleted, renamed paths.
 */
public final class AstDelta {

  /** {@code status} is A, M, D or R; {@code oldPath} is set only for R. */
  public record Change(char status, String path, String oldPath) {
  }

  private AstDelta() {
  }

  /**
   * Parses {@code git diff --name-status} output. A rename ({@code R<score>})
   * is kept as one change; the plan treats it as delete-old plus add-new.
   */
  public static List<Change> parseNameStatus(String text) {
    List<Change> out = new ArrayList<>();
    for (String line : text.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      String[] f = line.split("\t");
      char s = f[0].charAt(0);
      if (s == 'R' || s == 'C') {
        out.add(new Change(s == 'R' ? 'R' : 'A', f[2], s == 'R' ? f[1] : null));
      } else if (s == 'T') {
        out.add(new Change('M', f[1], null));
      } else {
        out.add(new Change(s, f[1], null));
      }
    }
    return out;
  }

  /**
   * The delta between {@code base} and {@code head} in {@code igRoot}, or
   * between {@code base} and the staged index when {@code head} is null.
   */
  public static List<Change> fromGit(String igRoot, String base, String head) {
    String out = head == null
        ? Toolchain.run(igRoot, "git", "diff", "--name-status", "-M", "--cached", base)
        : Toolchain.run(igRoot, "git", "diff", "--name-status", "-M", base, head);
    if (out == null) {
      return List.of();
    }
    return parseNameStatus(out);
  }
}
