package org.hl7.fhir.igtools.ast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * sha256 over the IG's INPUTS — the files the build reads — so a cached AST
 * can be checked against the source it claims to come from. Digesting the
 * AST itself would only prove the cache is intact, not that it is current.
 *
 * <p>Files are visited in sorted path order and each contributes its
 * relative path and its bytes, so a rename changes the digest.
 *
 * <p>Inside a git work tree the file set is what git counts as the tree:
 * tracked files plus untracked ones that are not ignored
 * ({@code git ls-files --cached --others --exclude-standard}). Without that,
 * a gitignored {@code .DS_Store} or Publisher scratch file under
 * {@code input/} makes the digest of a working checkout differ from a clean
 * clone of the same commit, and a seeded cache can never verify anywhere
 * else (folio-assistant bean wnhh, 2026-10-02: smart-trust recorded
 * {@code b2bbbfc4…}, a clean clone computes {@code c1023d82…}). Outside a work
 * tree, or without git, every file is hashed, as before. folio-assistant's
 * TypeScript twin ({@code fhir-harness/scripts/ig-ast.ts}) applies the same
 * rule.
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
    List<Path> files = gitTreeFiles(igRoot);
    if (files == null) {
      files = walkInputs(igRoot);
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

  /** Every regular file under the inputs: the rule outside a git work tree. */
  static List<Path> walkInputs(Path igRoot) throws IOException {
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
    return files;
  }

  /**
   * The input files git counts as the work tree, or {@code null} when
   * {@code igRoot} is not inside one or git cannot be run.
   */
  static List<Path> gitTreeFiles(Path igRoot) {
    try {
      String inside = run(igRoot, "git", "rev-parse", "--is-inside-work-tree");
      if (inside == null || !inside.trim().equals("true")) {
        return null;
      }
      List<String> cmd = new ArrayList<>(List.of("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--"));
      cmd.addAll(INPUTS);
      String out = run(igRoot, cmd.toArray(new String[0]));
      if (out == null) {
        return null;
      }
      Set<Path> files = new LinkedHashSet<>();
      for (String rel : out.split("\0")) {
        if (rel.isEmpty()) {
          continue;
        }
        Path p = igRoot.resolve(rel);
        // A tracked file deleted in the work tree is not an input any more.
        if (Files.isRegularFile(p)) {
          files.add(p);
        }
      }
      return new ArrayList<>(files);
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /** stdout of a command run in {@code dir}, or {@code null} on a non-zero exit. */
  private static String run(Path dir, String... cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false).start();
    p.getOutputStream().close();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (InputStream in = p.getInputStream()) {
      in.transferTo(buf);
    }
    p.getErrorStream().close();
    return p.waitFor() == 0 ? buf.toString(StandardCharsets.UTF_8) : null;
  }
}
