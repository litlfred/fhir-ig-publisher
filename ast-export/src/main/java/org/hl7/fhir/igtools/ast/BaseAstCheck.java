package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.hl7.fhir.utilities.json.model.JsonObject;

/**
 * Is the base AST what it claims to be? Review of PR #8, M3: the base was
 * never validated, so an AST built from a dirty tree, or copied from another
 * IG, was trusted as the build of {@code inputs.sourceRevision}.
 *
 * <p>The check recomputes {@link InputDigest} over a clean checkout of the
 * base revision (a detached {@code git worktree}, removed afterwards) and
 * compares it with {@code inputs.inputDigest}. Any mismatch, or anything
 * that cannot be determined, is a reason for a full build.
 */
public final class BaseAstCheck {

  private BaseAstCheck() {
  }

  /** Why the base AST cannot be trusted as the build of its revision, or null when it can. */
  public static String mismatch(Path ig, JsonObject manifest) {
    JsonObject inputs = manifest.has("inputs") && manifest.get("inputs").isJsonObject()
        ? manifest.getJsonObject("inputs") : null;
    if (inputs == null) {
      return "base AST records no inputs";
    }
    String rev = str(inputs, "sourceRevision");
    String digest = str(inputs, "inputDigest");
    if (rev == null) {
      return "base AST records no inputs.sourceRevision";
    }
    if (digest == null) {
      return "base AST records no inputs.inputDigest";
    }
    String actual;
    try {
      actual = digestAt(ig, rev);
    } catch (IOException e) {
      return "cannot recompute the input digest at " + rev + ": " + e.getMessage();
    }
    return digest.equals(actual) ? null
        : "base AST's inputDigest " + digest + " is not the input digest of " + rev + " (" + actual + ")";
  }

  /** {@link InputDigest} of a clean checkout of {@code rev}. */
  static String digestAt(Path ig, String rev) throws IOException {
    Path parent = Files.createTempDirectory("ast-export-base-");
    Path tree = parent.resolve("tree");
    try {
      Toolchain.Exec add = Toolchain.exec(ig.toString(), "git", "worktree", "add", "--detach", "--quiet",
          tree.toString(), rev + "^{commit}");
      if (!add.ok()) {
        throw new IOException("git worktree add failed: " + add.err().trim());
      }
      // The IG may sit below the repository root.
      String prefix = Toolchain.exec(ig.toString(), "git", "rev-parse", "--show-prefix").out().trim();
      return InputDigest.of(prefix.isEmpty() ? tree : tree.resolve(prefix));
    } finally {
      Toolchain.exec(ig.toString(), "git", "worktree", "remove", "--force", tree.toString());
      Toolchain.exec(ig.toString(), "git", "worktree", "prune");
      try (Stream<Path> s = Files.walk(parent)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  private static String str(JsonObject o, String n) {
    return o.has(n) && !o.get(n).isJsonNull() ? o.asString(n) : null;
  }
}
