package org.hl7.fhir.igtools.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r5.elementmodel.Element;

/**
 * Dependency edges among the LOGIC layer — Library, PlanDefinition, Measure,
 * ActivityDefinition — read from a resource's element tree.
 *
 * <p>This is the gap in upstream's {@code DependencyAnalyser}: its
 * {@code analysePD} and {@code analyseAD} are empty and Library and Measure
 * are not dispatched, so the part of an IG that changes most between
 * iterations has no edges. Filled here, beside it, rather than in it.
 *
 * <p>Also, for every resource type, {@code meta.profile}.
 *
 * <p>Unlike upstream, an edge whose target is NOT in this IG is kept and
 * marked unresolved: a dependency on another package is still a dependency,
 * and dropping it would make a rebuild cone look smaller than it is.
 */
public final class LogicEdges {

  /** Resource types whose outgoing edges this extracts. */
  public static final Set<String> TYPES = Set.of("Library", "PlanDefinition", "Measure", "ActivityDefinition");

  /** One outgoing edge. {@code targetVersion} is null when the reference pins none. */
  public record Edge(String kind, String target, String targetVersion, String path) {
  }

  private LogicEdges() {
  }

  public static List<Edge> of(String resourceType, Element root) {
    List<Edge> out = new ArrayList<>();
    if (root == null) {
      return out;
    }
    // Any resource, logic or not: an example is validated against the
    // profiles it claims, so a profile change must rebuild it. Upstream's
    // analyser records no such edge.
    Element meta = child(root, "meta");
    if (meta != null && meta.hasChildren()) {
      for (Element c : meta.getChildren()) {
        if ("profile".equals(c.getName())) {
          add(out, "meta.profile", c.getValue(), resourceType + ".meta.profile");
        }
      }
    }
    if (TYPES.contains(resourceType)) {
      walk(root, resourceType, out);
    }
    return out;
  }

  private static Element child(Element e, String name) {
    if (!e.hasChildren()) {
      return null;
    }
    for (Element c : e.getChildren()) {
      if (name.equals(c.getName())) {
        return c;
      }
    }
    return null;
  }

  private static void walk(Element e, String path, List<Edge> out) {
    if (!e.hasChildren()) {
      return;
    }
    for (Element c : e.getChildren()) {
      String name = c.getName();
      String p = path + "." + name;
      switch (name) {
        case "library" -> add(out, "library", c.getValue(), p);
        case "definitionCanonical" -> add(out, "action.definition", c.getValue(), p);
        case "relatedArtifact" -> {
          String type = AstExporter.childValue(c, "type");
          String ref = AstExporter.childValue(c, "resource");
          if ("depends-on".equals(type) || "composed-of".equals(type)) {
            add(out, "relatedArtifact." + type, ref, p + ".resource");
          }
        }
        case "valueSet" -> {
          // dataRequirement.codeFilter.valueSet — the terminology a CQL retrieve binds to
          if (path.endsWith(".codeFilter")) {
            add(out, "dataRequirement.valueSet", c.getValue(), p);
          }
        }
        default -> {
        }
      }
      walk(c, p, out);
    }
  }

  private static void add(List<Edge> out, String kind, String ref, String path) {
    if (ref == null || ref.isEmpty()) {
      return;
    }
    int bar = ref.indexOf('|');
    String target = bar < 0 ? ref : ref.substring(0, bar);
    String version = bar < 0 ? null : ref.substring(bar + 1);
    out.add(new Edge(kind, target, version, path));
  }
}
