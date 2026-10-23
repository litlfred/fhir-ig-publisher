#!/usr/bin/env bash
# W7's first real round, run by an agent or a person, not by CI: after
# run-real-igs.sh has built smart-immunizations,
# change ONE file, plan the delta, and run the incremental rebuild against the
# base AST. Every step prints what it did; nothing here is assumed to work.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "${1:?usage: w7-round.sh <work-dir used by run-real-igs.sh> [cql|fml]}" && pwd)"
# Which source to probe: a CQL file (default), or a FHIR Mapping Language file
# under input/maps/, whose StructureMap is not SUSHI output (bean qzex).
PROBE="${2:-cql}"
case "$PROBE" in cql|fml) ;; *) echo "probe must be cql or fml"; exit 2;; esac
IG="$WORK/smart-immunizations"
BASE="$IG/output-ast"
CP="$HERE/target/classes:$(cat "$WORK/cp.txt")"
OUT="$WORK/w7"; [ "$PROBE" = fml ] && OUT="$WORK/w7-fml"
mkdir -p "$OUT"

[ -f "$BASE/manifest.json" ] || { echo "no base AST at $BASE"; exit 1; }
# IncrementalBuildCli refuses a dirty work tree (untracked files included);
# the base AST lives at $IG/output-ast, so exclude it locally, never in .gitignore.
grep -qxF 'output-ast/' "$IG/.git/info/exclude" 2>/dev/null || echo 'output-ast/' >> "$IG/.git/info/exclude"
git -C "$IG" config user.email ci@example.invalid
git -C "$IG" config user.name ci
# The base AST records the IG's commit; make the delta a real commit on top.
if [ "$PROBE" = fml ]; then src=$(ls "$IG"/input/maps/*.fml | head -1); else src=$(ls "$IG"/input/cql/*.cql | head -1); fi
echo "changing: ${src#$IG/}"
printf '\n// ast-export W7 probe\n' >> "$src"
git -C "$IG" commit -qam "W7 probe: one $PROBE file"

echo "== plan"
java -cp "$CP" org.hl7.fhir.igtools.ast.AstPlanCli -ast "$BASE" -ig "$IG" -out "$OUT/plan.json"
python3 - "$OUT/plan.json" <<'PY'
import json, sys
p = json.load(open(sys.argv[1]))
print("decision:", p["decision"], " computed:", p.get("computedDecision"), " coneFraction:", p.get("coneFraction"))
print("seeds:", len(p["seeds"]), " rebuild:", len(p["rebuild"]), " load:", len(p["loadFromCache"]))
for w in p["fullBuildBecause"]: print("  full because:", w)
for f in p["files"]: print("  file:", f["path"], f["effect"], len(f["resources"]), f.get("reason", ""))
PY

echo "== incremental build"
java -Xmx8g -cp "$CP" org.hl7.fhir.igtools.ast.IncrementalBuildCli -ast "$BASE" -ig "$IG" -out "$OUT/ast" \
  -work "$OUT/work" -cache-folder "$OUT/pkg-cache" -threshold 0.99 2>&1 | tail -80
