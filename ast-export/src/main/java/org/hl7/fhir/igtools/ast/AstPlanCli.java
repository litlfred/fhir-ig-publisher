package org.hl7.fhir.igtools.ast;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/**
 * {@code AstPlanCli -ast <dir> -ig <dir> [-base <rev>] [-head <rev> | -staged] [-threshold 0.4] [-out plan.json]}
 *
 * <p>Writes the incremental plan for a delta against a base AST. {@code -base}
 * defaults to the AST's own {@code inputs.sourceRevision}. Builds nothing.
 */
public class AstPlanCli {

  public static void main(String[] args) throws Exception {
    String ast = AstExportCli.param(args, "-ast");
    String ig = AstExportCli.param(args, "-ig");
    if (ast == null || ig == null) {
      System.err.println("usage: AstPlanCli -ast <dir> -ig <dir> [-base <rev>] [-head <rev> | -staged] [-threshold 0.4] [-out plan.json] [-fsh-users fsh-file-users.json]");
      System.exit(2);
    }
    String base = AstExportCli.param(args, "-base");
    if (base == null) {
      JsonObject m = JsonParser.parseObject(Files.readString(Path.of(ast, "manifest.json")));
      JsonObject inputs = m.getJsonObject("inputs");
      base = inputs == null || !inputs.has("sourceRevision") || inputs.get("sourceRevision").isJsonNull()
          ? null : inputs.asString("sourceRevision");
      if (base == null) {
        System.err.println("the AST records no sourceRevision; pass -base");
        System.exit(2);
      }
    }
    String head = AstExportCli.has(args, "-staged") ? null : AstExportCli.param(args, "-head");
    if (head == null && !AstExportCli.has(args, "-staged")) {
      head = "HEAD";
    }
    String t = AstExportCli.param(args, "-threshold");
    double threshold = t == null ? IncrementalPlan.DEFAULT_THRESHOLD : Double.parseDouble(t);
    List<AstDelta.Change> delta = AstDelta.fromGit(ig, base, head);
    String fu = AstExportCli.param(args, "-fsh-users");
    JsonObject plan = new IncrementalPlan(Path.of(ast), fu == null ? null : IncrementalPlan.readFshUsers(Path.of(fu)))
        .plan(delta, threshold);
    plan.add("base", base);
    plan.add("head", head == null ? "(staged)" : head);
    String json = JsonParser.compose(plan, true);
    String out = AstExportCli.param(args, "-out");
    if (out != null) {
      Files.writeString(Path.of(out), json);
    } else {
      System.out.println(json);
    }
  }
}
