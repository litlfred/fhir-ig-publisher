package org.hl7.fhir.igtools.ast;

import java.nio.file.Path;

import org.hl7.fhir.igtools.publisher.Publisher;
import org.hl7.fhir.igtools.publisher.PublisherUtils;
import org.hl7.fhir.utilities.Utilities;

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
    if (param(args, "-tx") != null) {
      p.getSettings().setTxServer(param(args, "-tx"));
    }
    if (has(args, "-no-sushi")) {
      p.getSettings().setNoSushi(true);
    }
    p.getSettings().setCacheOption(PublisherUtils.CacheOption.LEAVE);
    // The inputs, and so the digest, BEFORE the build writes anything under input/.
    p.recordInputs(igRoot(ig).toString());
    p.execute();
    String out = param(args, "-ast-out");
    Path outDir = out != null ? Path.of(out) : Path.of(Utilities.path(ig, "output-ast"));
    p.exportAst(outDir);
    System.out.println("AST written to " + outDir.toAbsolutePath());
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
