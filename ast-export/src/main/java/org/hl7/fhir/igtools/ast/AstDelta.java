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
   * Parses {@code git diff --name-status -z} output: NUL-separated fields, so
   * a path holding a tab, a newline or a quote is read as git wrote it rather
   * than C-quoted.
   */
  public static List<Change> parseNameStatusZ(String text) {
    List<Change> out = new ArrayList<>();
    String[] f = text.split("\0", -1);
    int i = 0;
    while (i < f.length && !f[i].isEmpty()) {
      char s = f[i].charAt(0);
      if (s == 'R' || s == 'C') {
        out.add(new Change(s == 'R' ? 'R' : 'A', f[i + 2], s == 'R' ? f[i + 1] : null));
        i += 3;
      } else {
        out.add(new Change(s == 'T' ? 'M' : s, f[i + 1], null));
        i += 2;
      }
    }
    return out;
  }

  /**
   * The delta between {@code base} and {@code head} in {@code igRoot}, or
   * between {@code base} and the staged index when {@code head} is null.
   *
   * <p>A git failure (a base revision missing from a shallow clone, a path
   * that is not a repository) is NOT an empty delta: it throws, with git's
   * own stderr as the reason, and the caller builds in full. An empty list
   * means git ran and found no change. (Review of PR #8, B1.)
   */
  public static List<Change> fromGit(String igRoot, String base, String head) throws FullBuildRequired {
    if (base == null) {
      throw new FullBuildRequired("no base revision: the base AST records no inputs.sourceRevision");
    }
    Toolchain.Exec e = head == null
        ? Toolchain.exec(igRoot, "git", "diff", "--name-status", "-z", "-M", "--cached", base, "--")
        : Toolchain.exec(igRoot, "git", "diff", "--name-status", "-z", "-M", base, head, "--");
    if (!e.ok()) {
      throw new FullBuildRequired("git diff " + base + ".." + (head == null ? "(staged)" : head) + " failed (exit "
          + e.exit() + "): " + e.err().trim());
    }
    return parseNameStatusZ(e.out());
  }
}
