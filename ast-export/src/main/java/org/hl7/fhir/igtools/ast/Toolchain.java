package org.hl7.fhir.igtools.ast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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

  static String run(String dir, String... cmd) {
    try {
      Process p = new ProcessBuilder(cmd).directory(new File(dir)).redirectErrorStream(true).start();
      String out;
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        out = r.lines().reduce((a, b) -> a + "\n" + b).orElse("").trim();
      }
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return null;
      }
      return p.exitValue() == 0 && !out.isEmpty() ? out : null;
    } catch (Exception e) {
      return null;
    }
  }
}
