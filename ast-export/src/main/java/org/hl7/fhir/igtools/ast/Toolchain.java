package org.hl7.fhir.igtools.ast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.hl7.fhir.igtools.publisher.IGVersionUtil;
import org.hl7.fhir.utilities.VersionUtil;
import org.hl7.fhir.utilities.json.model.JsonObject;

/**
 * What the AST was built WITH. An AST is valid for one toolchain and one
 * source revision, so a consumer compares these before trusting it.
 *
 * <p>A value that cannot be determined is written as {@code null} with a
 * reason beside it, never as an empty string: "unknown" and "none" are
 * different answers.
 */
public final class Toolchain {

  private Toolchain() {
  }

  public static JsonObject describe(String igRoot) {
    JsonObject t = new JsonObject();
    t.add("publisher", IGVersionUtil.getVersion());
    t.add("core", VersionUtil.getVersion());
    String sushi = run(igRoot, "sushi", "--version");
    if (sushi != null) {
      t.add("sushi", sushi);
    } else {
      t.addNull("sushi");
      t.add("sushiUnknownBecause", "`sushi --version` did not run in " + igRoot);
    }
    t.add("java", System.getProperty("java.version"));
    return t;
  }

  /** The IG source's git commit, or null when the IG is not a git checkout. */
  public static String sourceRevision(String igRoot) {
    return run(igRoot, "git", "rev-parse", "HEAD");
  }

  /** Trimmed stdout of a command, or null on any failure or empty output. */
  static String run(String dir, String... cmd) {
    Exec e = exec(dir, cmd);
    return e.ok() && !e.out().trim().isEmpty() ? e.out().trim() : null;
  }

  /**
   * One command's outcome: exit code, stdout and stderr KEPT APART, so a
   * caller can tell "succeeded with no output" from "failed" and report why.
   * {@code exit} is -1 when the command could not be started or timed out.
   */
  public record Exec(int exit, String out, String err) {
    public boolean ok() {
      return exit == 0;
    }
  }

  static Exec exec(String dir, String... cmd) {
    File errFile = null;
    try {
      errFile = File.createTempFile("ast-export-", ".stderr");
      Process p = new ProcessBuilder(cmd).directory(new File(dir))
          .redirectError(ProcessBuilder.Redirect.to(errFile)).start();
      p.getOutputStream().close();
      String out;
      try (var in = p.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return new Exec(-1, out, "timed out after 60s");
      }
      String err = java.nio.file.Files.readString(errFile.toPath(), StandardCharsets.UTF_8);
      return new Exec(p.exitValue(), out, err);
    } catch (Exception e) {
      return new Exec(-1, "", String.valueOf(e));
    } finally {
      if (errFile != null) {
        errFile.delete();
      }
    }
  }
}
