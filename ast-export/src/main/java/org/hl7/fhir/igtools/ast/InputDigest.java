package org.hl7.fhir.igtools.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * sha256 over the IG's INPUTS — the files the build reads — so a cached AST
 * can be checked against the source it claims to come from. Digesting the
 * AST itself would only prove the cache is intact, not that it is current.
 *
 * <p>Files are visited in sorted path order and each contributes its
 * relative path and its bytes, so a rename changes the digest.
 */
public final class InputDigest {

  /** Paths under the IG root that a build reads. Absent ones are skipped. */
  static final List<String> INPUTS = List.of("sushi-config.yaml", "ig.ini", "input");

  private InputDigest() {
  }

  public static String of(Path igRoot) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    List<Path> files = new ArrayList<>();
    for (String in : INPUTS) {
      Path p = igRoot.resolve(in);
      if (Files.isRegularFile(p)) {
        files.add(p);
      } else if (Files.isDirectory(p)) {
        try (Stream<Path> s = Files.walk(p)) {
          s.filter(Files::isRegularFile).forEach(files::add);
        }
      }
    }
    files.sort(null);
    for (Path f : files) {
      md.update(igRoot.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(Files.readAllBytes(f));
      md.update((byte) 0);
    }
    return HexFormat.of().formatHex(md.digest());
  }
}
