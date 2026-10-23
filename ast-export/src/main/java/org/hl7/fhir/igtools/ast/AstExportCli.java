package org.hl7.fhir.igtools.ast;

import java.nio.file.Path;

import org.hl7.fhir.igtools.publisher.Publisher;
import org.hl7.fhir.igtools.publisher.PublisherUtils;

/**
 * {@code java -cp ... org.hl7.fhir.igtools.ast.AstExportCli -ig <dir> [-ast-out <dir>] [-tx <url>] [-no-sushi]}
 *
 * <p>Runs one ordinary IG build, then writes the AST. The AST goes to
 * {@code -ast-out}, default {@code <ig>/output-ast}, OUTSIDE the Publisher's
 * {@code output/}, so the published output is the Publisher's alone.
 *
 * <p>Takes only the handful of flags an iterative build needs; everything
 * else is the Publisher's default. For the full flag set, run the stock
 * Publisher.
 */
public class AstExportCli {

  public static void main(String[] args) throws Exception {
    String ig = param(args, "-ig");
    if (ig == null) {
      System.err.println("usage: AstExportCli -ig <dir> [-ast-out <dir>] [-tx <url>] [-no-sushi]");
      System.exit(2);
    }
    AstPublisher p = new AstPublisher();
    p.setConfigFile(Publisher.getAbsoluteConfigFilePath(
        Publisher.determineActualIG(ig, PublisherUtils.IGBuildMode.MANUAL)));
    // The Publisher's own rule, reused: -tx, else -devtx, else tx.fhir.org.
    // Setting it only when -tx was given left the AST build with NO
    // terminology server while a stock build used tx.fhir.org (bean qzex,
    // 2026-10-23: 2261 differing output files).
    Publisher.setTxServerValue(args, p);
    if (has(args, "-no-sushi")) {
      p.getSettings().setNoSushi(true);
    }
    p.getSettings().setCacheOption(PublisherUtils.CacheOption.LEAVE);
    // The inputs, and so the digest, BEFORE the build writes anything under input/.
    p.recordInputs(igRoot(ig).toString());
    p.execute();
    String out = param(args, "-ast-out");
    Path outDir = out != null ? Path.of(out) : defaultAstOut(ig);
    p.exportAst(outDir);
    System.out.println("AST written to " + outDir.toAbsolutePath());
  }

  /**
   * {@code <ig root>/output-ast}. Resolved from the absolute IG root, because
   * {@code Utilities.path(".", "output-ast")} refuses a relative first element,
   * and run-real-igs.sh passes {@code -ig .}.
   */
  static Path defaultAstOut(String ig) {
    return igRoot(ig).resolve("output-ast");
  }

  /** The IG's root directory: {@code -ig} names it, or names a file inside it (ig.ini). */
  static Path igRoot(String ig) {
    Path p = Path.of(ig).toAbsolutePath().normalize();
    return java.nio.file.Files.isDirectory(p) ? p : p.getParent();
  }

  static String param(String[] args, String name) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    return null;
  }

  static boolean has(String[] args, String name) {
    for (String a : args) {
      if (a.equals(name)) {
        return true;
      }
    }
    return false;
  }
}
