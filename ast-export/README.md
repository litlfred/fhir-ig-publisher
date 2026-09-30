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
- `inputs` carries `toolchain`, `sourceRevision` (the IG's git commit) and
  `inputDigest` (sha256 over `sushi-config.yaml`, `ig.ini` and `input/`), so a
  consumer can tell whether a cached AST was built from the source it has now.

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

## Incremental plan (W5, W6)

`AstPlanCli` takes a base AST and a delta (a commit range, a PR diff, or the
staged index via `-staged`) and writes `ig-ast-plan/v1`. It **builds nothing**.

```sh
java -cp "target/classes:$(cat cp.txt)" org.hl7.fhir.igtools.ast.AstPlanCli \
  -ast /path/to/ig/output-ast -ig /path/to/ig [-base <rev>] [-head <rev> | -staged] [-threshold 0.4]
```

1. **Files to resources.** A resource's own source, SUSHI's `fsh-index.json`
   for `.fsh`, a `.cql` file to the Library of the same name, and, when
   `fsh-cone` supplies them, a RuleSet file to the files that insert it.
2. **Classification.** `RESOURCES`, `NEW_SOURCE`, `RENDER_ONLY` (`input/pagecontent/`
   and similar) or `FULL_BUILD`. Build configuration, and any file whose effect
   **cannot be determined**, forces a full build with the reason. "Cannot tell"
   is never treated as incremental.
3. **The cone.** `rebuild` is the forward cone: everything that depends on a
   changed resource, transitively. `loadFromCache` is what the rebuilt set
   depends on. A deleted file's resources are listed under `remove`, and their
   dependents are rebuilt.
4. **Decision.** `incremental`, or `full` with `fullBuildBecause`. A cone
   above `-threshold` of the IG (default 40%) is a full build, because a full
   build costs about the same and is simpler.

**The cone is provisional until rebuilt.** It is computed on the base edges,
and a rebuilt resource can gain edges the base did not have. W7 must
recompute the cone after rebuilding and repeat until it stops growing.

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
was unreachable from the environment this was written in. W7: the rebuild itself. First choice: pack the `loadFromCache` part as a
local NPM package and run the stock Publisher on a temporary IG holding only
`rebuild`. W8: diff a full build against the incremental AST, and the
threshold. W3: pinned dependency closure and terminology provenance. W4:
page-fragment provenance.
