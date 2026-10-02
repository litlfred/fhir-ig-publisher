# ig-ast-export

An **AST export for the FHIR IG Publisher, built on top of it** — a separate
library, not a change to the Publisher. Nothing outside this directory is
modified, and it is not a module of the root `pom.xml`. It depends on the
**released** `org.hl7.fhir.publisher.core` from Maven Central
(`publisher.version` in `pom.xml`), so it follows upstream by a version bump.

## What it does (W1)

Runs one ordinary IG build, then writes what the build holds in memory:

```
<ig>/output-ast/
  manifest.json                      # ig-ast/v1
  dependencies.json                  # ig-ast-dependencies/v1 (W2)
  fsh-index.json                     # SUSHI's .fsh -> output map, kept for later deltas
  resources/<ResourceType>/<id>.json # one file per loaded resource
```

- every resource is keyed by **`canonical|version`**, or `Type/id` when it
  has no canonical;
- `toolchain` records the publisher, core, SUSHI and Java versions; a value
  that cannot be determined is `null` **with the reason beside it**;
- `inputs` is **exactly** folio-assistant's strict `CompiledInputsSchema`:
  - `toolchain`;
  - `sourceRevision`, the IG's git commit, omitted when unknown, with the reason
    in `inputsUnknown`;
  - `inputDigest`, a sha256 over `sushi-config.yaml`, `ig.ini` and `input/`,
    written as 64 lowercase hex characters.

  Beside `inputs`, never inside it, `inputDigestMode` says which rule chose the
  files: `git` (what git counts as the work tree) or `walk` (every file, only
  outside a work tree or without git), with the reason. A git failure inside a
  work tree is an error, not a silent switch to `walk`.
- each resource's `source` is relative to the IG root, even though upstream's
  fetcher records an absolute path.

  A consumer runs its `compiledValidity` staleness check on it unchanged.

**An AST is a cache, never an authority.** The manifest says
`"authority": "cache"` and lists what is `provisional` until a full Publisher
run — indices, dependency edges, versions. Anything rendered from it must
carry that mark to the reader.

The Publisher's `output/` is untouched: the AST is written to `-ast-out`,
default `<ig>/output-ast`, after the build finishes.

## Dependency edges (W2)

`dependencies.json` lists every edge with `source` (a resource key), `kind`,
`target`, `targetVersion`, `resolved` and `origin`:

- **`origin: publisher`**: upstream's own `DependencyAnalyser`, reused
  unchanged, which covers terminology and conformance resources;
- **`origin: ast-export`**: `LogicEdges`, the gap upstream leaves, covering
  Library (`relatedArtifact` depends-on / composed-of,
  `dataRequirement.codeFilter.valueSet`), PlanDefinition and ActivityDefinition
  (`library`, `action.definitionCanonical`, nested actions included), and Measure
  (`library`).

`resolved` names the resource in THIS IG that the edge lands on, or `null`
when the target is elsewhere, such as another package's FHIRHelpers. Unlike
upstream, such an edge is **kept**. A dependency on another package is still
a dependency, and dropping it makes a rebuild cone look smaller than it is.

## The incremental guard: every decision is `full` for now

The code review of this PR (2026-10-02) found ways the incremental path could
drop a resource or keep a stale one. Until those are fixed **and** W7 has been
run against a real Publisher and diffed against a full build,
`IncrementalPlan.INCREMENTAL_GUARD` is on, and it is applied in one place,
`IncrementalPlan.guard`, by both CLIs:

- `AstPlanCli` still computes and writes the whole plan, but its `decision` is
  `full`, with `"incremental path not yet verified"` in `fullBuildBecause`. The
  decision it would have made is kept as `computedDecision`, for review.
- `IncrementalBuildCli` therefore always runs one ordinary build. The plan is
  still written to `<work>/plan.json`.

So W1 and W2 (`AstExporter`, `LogicEdges`, `InputDigest`) can merge safely:
nothing acts on an incremental decision. The owner lifts the guard in a
separate change, after review.

**What lifting it needs.** The review's blockers and majors on the
incremental path (B1-B3, M1-M6) are fixed on this branch, each with a
regression test. That is necessary, not sufficient: the W7 loop has still
never run against a real Publisher. Lift it once one real incremental round
has been run and its AST diffed against a full build of the same revision
(W8) with every difference explained.

## Incremental plan (W5, W6)

`AstPlanCli` takes a base AST and a delta (a commit range, a PR diff, or the
staged index via `-staged`) and writes `ig-ast-plan/v1`. It **builds nothing**.

```sh
java -cp "target/classes:$(cat cp.txt)" org.hl7.fhir.igtools.ast.AstPlanCli \
  -ast /path/to/ig/output-ast -ig /path/to/ig [-base <rev>] [-head <rev> | -staged] [-threshold 0.4] \
  [-fsh-users <json>] [-head-fsh-index <ig>/fsh-generated/data/fsh-index.json]
```

Before any file is classified, three things force `full`, with the reason:

- **The delta cannot be read.** A `git diff` failure, such as a base revision
  missing from a shallow clone, is never an empty delta.
- **The base AST is not what it claims.** `inputs.inputDigest` is recomputed
  over a clean, detached `git worktree` of `inputs.sourceRevision`, which is
  removed afterwards. A mismatch, or a missing revision or digest, is a full
  build.

1. **Files to resources.** A resource's own source, SUSHI's `fsh-index.json`
   for `.fsh`, a `.cql` file to the Library of the same name, and a RuleSet or
   Alias file to the files that use it. The last comes from `-fsh-users <json>`,
   written by folio-assistant's
   `bun run cat-harness/content/pipeline/fsh-cone.ts <ig> --file-users <json>`
   (`fsh-file-users/v1`). **Without it, ANY changed or deleted `.fsh` file is
   a full build**: a file that declares resources may also declare RuleSets,
   Aliases or parents that other files use. An empty users map is an answer;
   an absent one is not.
2. **Classification.** `RESOURCES`, `NEW_SOURCE`, `RENDER_ONLY` (`input/pagecontent/`
   and similar) or `FULL_BUILD`. Build configuration, and any file whose effect
   **cannot be determined**, forces a full build with the reason. "Cannot tell"
   is never treated as incremental.
3. **The cone.** `rebuild` is the forward cone: everything that depends on a
   changed resource, transitively. `loadFromCache` is what the rebuilt set
   depends on. **A key is removed only when head no longer defines it:**
   - only what a deleted file itself DEFINES is a candidate; a deleted `.cql`
     changes its Library, which is rebuilt;
   - a deleted `.fsh` file's resources are removed only when head's fsh-index
     (`-head-fsh-index`) no longer generates them, and are rebuilt when they
     moved to another file. Without head's index, the plan is full;
   - a key that one change removes and another rebuilds is a full build;
   - a new `.cql` file is a full build.

   Dependents of removed resources are rebuilt.
4. **Decision.** `incremental`, or `full` with `fullBuildBecause`. **While the
   guard is on, the written decision is always `full`** (see above). A cone
   above `-threshold` of the IG (default 40%) is a full build, because a full
   build costs about the same and is simpler.

**The cone is provisional until rebuilt.** It is computed on the base edges,
and a rebuilt resource can gain edges the base did not have. W7 must
recompute the cone after rebuilding and repeat until it stops growing.

## Incremental rebuild (W7), UNTESTED END TO END, and GUARDED

While the guard is on, step 1 always decides `full` and steps 2 to 7 do not
run.

`IncrementalBuildCli -ast <base> -ig <dir> -out <dir> [-head <rev> | -staged] [-work <dir>] [-cache-folder <dir>] [-fsh-users <json>]`

0. **Refuse a work tree that is not the revision planned** (exit 2). SUSHI and
   the Publisher read the work tree, so it must be clean, untracked files
   included, with `HEAD` at `-head`; or, with `-staged`, have no unstaged edits
   and no untracked files. (The smaller safe option: there is no separate
   `git worktree` build.)
1. Run SUSHI on the real IG, so `fsh-generated/` and its index match head.
2. Plan the delta (above), with head's fsh-index. A `full` decision runs one
   ordinary build. Anything later that cannot be accounted for also ends in one
   ordinary build, with the reason.
3. **`CachePackageWriter`** writes every resource not being rebuilt as an
   ordinary FHIR package, `<IG packageId>.ast-cache#0.0.0-ast.<base>.r<round>`,
   into the package cache folder, after removing every earlier
   `<IG packageId>.ast-cache#*`. The default cache is a scratch
   `<work>/package-cache`, rebuilt every run, that links the user's packages;
   the default `<work>` is a temp directory outside the IG. Each round's
   directories are emptied before use. The stock Publisher then loads the
   cached part as a dependency, which it already knows how to do.
4. **`TempIgAssembler`** builds a temporary IG with only the rebuild set's
   sources (and a rebuilt Library's `.cql`), plus each new source's resource
   files: a new `.fsh` file's outputs from `fsh-generated/data/fsh-index.json`,
   or a new `input/**.json|xml` itself. A new source that yields no resource
   file is a full build. Its ImplementationGuide lists only
   those resources, gains `dependsOn` the cache package, and has one stub page:
   the result is an AST, not a site.
5. The stock Publisher builds that IG (`-no-sushi`), and its AST is exported.
6. **`AstMerger`** merges it into the base as a **mixed-provenance** AST:
   every resource carries `builtAt`, and the manifest carries `mixed: true` and
   `incremental.{base, head, rebuilt, kept, removed}`. Edges are re-resolved
   against the merged set, and the merged AST carries **head's** fsh-index.
   A resource the partial build was asked to rebuild but did not produce is a
   full build, unless head's fsh-index shows its definition removed, when it is
   counted as removed.
7. **Fixed point.** If the merged graph's cone reaches resources that were not
   rebuilt, they are rebuilt next round. `-max-rounds` (default 3), then give
   up and ask for a full build.

Each piece has unit tests. **The loop has never run against a real
Publisher**: the package registry was unreachable where this was written.
Risks to check first:

- **Canonical collision.** The cache package and the temporary IG share the
  IG's canonical base. The Publisher may object to a dependency that
  publishes into its own canonical space.
- **References to the IG's own pages or resources** that the cut-down IG no
  longer has, from narratives or `definition.grouping`.
- **CQL includes.** `CqlSubSystem` must find a cached Library's CQL source
  inside the cache package, rather than in `input/cql/`.
- **A temporary IG entry that is not `Type-id.json`**, such as a hand-written
  resource under `input/`, does not match `definition.resource`'s reference.
- **The scratch package cache** links the user's packages. The Publisher may
  write into a linked package folder as it would into `~/.fhir/packages`.

## How it builds on the Publisher without changing it

| piece | how |
|---|---|
| `AstPublisher` | `extends Publisher`, overrides nothing; `exportAst()` reads `getFileList()` after `execute()` |
| `AstFieldsAccess` | read-only view of package-private `PublisherFields`, declared in the Publisher's package **inside this library**; a rename upstream fails to compile rather than at run time |
| `AstExportCli` | a launcher in the style of upstream's `Publisher.publishDirect`, because `Publisher.main` constructs a plain `Publisher` and cannot be handed a subclass |
| `AstExporter` | pure: files in, dump + manifest out; unit-tested without a build |

The snapshot is taken **after** the build, so resources carry the narratives
the build generated. A snapshot before rendering would need a hook upstream
does not expose.

## The consumer half lives in folio-assistant

This library **produces** ASTs. Reading, checking and showing them is done in
`litlfred/folio-assistant`, under `fhir-harness/`:

- `scripts/ig-ast.ts`:
  - `list`;
  - `validity`: `compiledValidity` on `inputs`, with the input digest recomputed
    by the same algorithm; a golden vector is asserted in both test suites;
  - `diff`: resources and edges, with an element-level differential;
  - `render`: just-the-docs pages, each opening with the provisional mark.
- The skill `fhir-ig-base/ig-ast-delta`, and the six Tools declared in
  `fhir-harness/tools/index.ts`.
- `cat-harness/processes/ig-ast-delta-review.bpmn`, the review step of an
  incremental build: check validity, diff and render, then "every difference
  explained?". An unexplained difference is a missed coupling and ends in a
  full build.
- `fsh-cone --file-users` writes the `-fsh-users` input for `AstPlanCli`.

## Without packages.fhir.org: seeding the cache from trusted sources

```sh
ast-export/scripts/seed-fhir-cache-from-npm.py [--cache DIR] [--sushi-config FILE] [--mirror DIR|GIT-URL]
    [--mirror-commit SHA] [--template-repo NAME=OWNER/REPO] [--missing-out FILE] [--dry-run] [name#version ...]
ast-export/scripts/mirror-fhir-packages.sh <mirror-repo-dir> <missing.txt | name#version ...>   # on a machine WITH packages.fhir.org
```

Sources, in order. Each is a trust anchor named by the owner or by the
package's own publisher:

| source | covers | integrity |
|---|---|---|
| npm, account **`grahamegrieve`** (owner: trusted; he founded HL7 FHIR) | mostly the latest version of each HL7 package; the core packages under `@hl7/` | that VERSION's `_npmUser` must be `grahamegrieve` (being a maintainer is not enough); npm's published sha512; always `--registry https://registry.npmjs.org/` |
| the publisher's own site repo: `WorldHealthOrganization/smart-html`, `IHE/publications` | every released WHO `smart.who.int.*` and IHE version | the branch is resolved to a commit first and the file fetched BY that commit, which is recorded; the tarball's own `package.json` must name the exact package and version; sha512 recorded |
| a template's own repo at HEAD, found through `FHIR/ig-registry/templates.json`, or `--template-repo` for one the registry does not list (`who.template.root`) | templates, including `#current` | the commit is recorded. Owner: `fhir.base.template` is trusted |
| `--mirror`, filled by `mirror-fhir-packages.sh` | everything else, typically the pinned HL7 versions | each file must match the mirror's `SHA512SUMS`; a git mirror is fetched at `--mirror-commit`, or at its HEAD resolved live before the clone, every git step's exit code is checked, and the commit is recorded; then as for site repos. The person who ran the mirror is the trust anchor |

A package already in the cache must name exactly the package and version
asked for in its `package.json` (a `current` template by name), or it is
refused. A name or version that starts with `-` or holds a path separator or
`..` never reaches a path or an npm argument. Packages are unpacked beside
their destination and renamed into place. `mirror-fhir-packages.sh` downloads
over https only, redirects included, and checks a file already present.

The checks have offline tests, every network call mocked:
`python3 -m unittest discover -s ast-export/scripts -p 'test_*.py'`.

**Nothing is computed once and kept** (owner, 2026-10-01: *"dynamically load
from repos... dont calc once and assume fixed. avoid drift"*). The template
registry, the IHE folder listing and any mirror clone are read fresh on every
run, in a scratch directory deleted at exit.

**Exact versions only**, with one rule copied from the Publisher: a patch
wildcard (`1.1.x`) resolves to the highest `1.1.N` a source lists on this run,
and the resolution is recorded. `dev` and other ranges are reported.

**Measured 2026-10-01, both WHO IGs together:** 20 packages install from these
sources, including both templates. The missing ones are the pinned HL7 versions
(IPS, terminology, extensions, CQL, CRMI, SDC, IPA), `fhir.cqf.common` and
`us.nlm.vsac`. Run with `--missing-out missing.txt`, mirror that list from a
machine with packages.fhir.org, and run again with `--mirror`.

## Scripts are the tools; CI calls them, never re-implements them

`scripts/run-real-igs.sh` (W1/W2 measurements) and `scripts/w7-round.sh` (one
incremental round) are what an agent or a person runs. Owner, 2026-09-30:
**no GitHub Actions for now**, and if CI is ever added it **calls these same
scripts**. It holds no logic of its own, so a CI result and a local result
are the same measurement.

## Run on real IGs, one command

```sh
ast-export/scripts/run-real-igs.sh [work-dir] [--byte-identical]
```

The script checks the tools and the network, then runs the unit tests. It
then builds `WorldHealthOrganization/smart-trust` and `smart-immunizations`
through `AstExportCli` and prints the measurements:

- resource and edge counts;
- how many sources sit under `fsh-generated/resources/`;
- **W2 per resource type** against 458 of 458;
- edges that point outside the IG.

`--byte-identical` also builds smart-trust with the stock Publisher and diffs
the two `output/` trees.

## Run

```sh
cd ast-export
mvn -q package
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" org.hl7.fhir.igtools.ast.AstExportCli -ig /path/to/ig [-ast-out dir] [-tx url] [-no-sushi]
```

## Not yet

W2's exit criterion, all 458 logic artefacts of smart-immunizations carrying
their edges, has not been measured on a real build yet. The package registry
was unreachable from the environment this was written in. W7 end-to-end run. W8: diff a full build against the incremental AST, and the
threshold. W3: pinned dependency closure and terminology provenance. W4:
page-fragment provenance.
