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
# Start every probe from the base AST's own revision, with a clean tree. A
# Publisher build leaves untracked files under input/ (generated page
# content), and IncrementalBuildCli refuses a dirty tree. This IG clone is the
# script's scratch copy, so resetting it loses nothing; output-ast/ is kept
# because it is excluded above.
REV=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["inputs"]["sourceRevision"])' "$BASE/manifest.json")
git -C "$IG" reset -q --hard "$REV"
git -C "$IG" clean -fdq
# The base AST records the IG's commit; make the delta a real commit on top.
if [ "$PROBE" = fml ]; then src=$(ls "$IG"/input/maps/*.fml | head -1); else src=$(ls "$IG"/input/cql/*.cql | head -1); fi
echo "changing: ${src#$IG/}"
if [ "$PROBE" = fml ]; then
  # A comment compiles away, so the StructureMap would not change (fpmn,
  # 2026-10-23). Rename the first rule instead: rule names are kept in
  # StructureMap.group.rule.name.
  python3 - "$src" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
t, n = re.subn(r'"([A-Za-z][A-Za-z0-9]*)";', r'"\1W7Probe";', s, count=1)
if n != 1: sys.exit("no named rule to rename in " + p)
open(p, "w").write(t)
PY
else
  # The Library embeds the CQL source, so a comment is a real change.
  printf '\n// ast-export W7 probe\n' >> "$src"
fi
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
# A failed incremental build must not stop W8 below (bean wv7r).
set +e
java -Xmx8g -cp "$CP" org.hl7.fhir.igtools.ast.IncrementalBuildCli -ast "$BASE" -ig "$IG" -out "$OUT/ast" \
  -work "$OUT/work" -cache-folder "$OUT/pkg-cache" -threshold 0.99 2>&1 | tail -80
echo "incremental exit ${PIPESTATUS[0]}"
set -e

# W8: one ordinary full build of the same commit, from a clean tree, to diff
# against the incremental AST. The incremental build above leaves files
# under input/, so clean first (bean fpmn).
echo "== W8 full build"
W8="$WORK/w8-$PROBE"; mkdir -p "$W8"
git -C "$IG" reset -q --hard HEAD
git -C "$IG" clean -fdq
git -C "$IG" rev-parse HEAD > "$W8/commit.txt"
set +e
(cd "$IG" && java -Xmx8g -cp "$CP" org.hl7.fhir.igtools.ast.AstExportCli -ig . -ast-out "$W8/ast" > "$W8.log" 2>&1)
echo "w8 $PROBE exit $?"
set -e
