#!/usr/bin/env bash
# Run the AST export on real IGs and print the W1/W2 measurements.
#
#   ast-export/scripts/run-real-igs.sh [work-dir] [--byte-identical]
#
# work-dir defaults to ../../ast-real-igs (beside this checkout). Needs network
# to packages.fhir.org, packages2.fhir.org and tx.fhir.org, plus: JDK 17+,
# Maven, SUSHI (npm i -g fsh-sushi), Jekyll (gem install jekyll), git,
# python3. Nothing is written outside work-dir except the FHIR package cache.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$HERE/../../ast-real-igs}"
BYTE=0
for a in "$@"; do [ "$a" = "--byte-identical" ] && BYTE=1; done
[ "${1:-}" = "--byte-identical" ] && WORK="$HERE/../../ast-real-igs"
mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd)"

need() { command -v "$1" >/dev/null || { echo "missing: $1 ($2)"; exit 1; }; }
need java "JDK 17+"; need mvn "Maven"; need sushi "npm i -g fsh-sushi"
need jekyll "gem install jekyll"; need git "git"; need python3 "python3"
curl -s -o /dev/null -m 20 -w '%{http_code}' https://packages.fhir.org/hl7.fhir.uv.extensions.r5 | grep -qv '^000$' \
  || { echo "packages.fhir.org unreachable: the build cannot fetch dependencies"; exit 1; }

echo "== unit tests"
(cd "$HERE" && mvn -q test && mvn -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt")
CP="$HERE/target/classes:$(cat "$WORK/cp.txt")"

run_ig() {
  local name="$1" dir="$WORK/$1"
  if [ -d "$dir/.git" ]; then git -C "$dir" pull -q; else git clone -q --depth 1 "https://github.com/WorldHealthOrganization/$name" "$dir"; fi
  echo "== $name: build + AST export (log: $WORK/$name.log)"
  (cd "$dir" && java -Xmx8g -cp "$CP" org.hl7.fhir.igtools.ast.AstExportCli -ig . >"$WORK/$name.log" 2>&1) \
    || { echo "build FAILED, see $WORK/$name.log"; tail -20 "$WORK/$name.log"; return 1; }
}

summarise() {
  python3 - "$WORK/$1/output-ast" <<'PY'
import json, sys, collections, os
d = sys.argv[1]
m = json.load(open(os.path.join(d, "manifest.json")))
deps = json.load(open(os.path.join(d, "dependencies.json")))["dependencies"]
res = m["resources"]
print(f"  resources: {len(res)}   edges: {len(deps)}   files kept: fsh-index.json={os.path.exists(os.path.join(d,'fsh-index.json'))}")
src = collections.Counter(("fsh-generated/resources/" in (r.get("source") or "")) for r in res)
print(f"  sources under fsh-generated/resources/: {src[True]} of {len(res)}  (IncrementalPlan assumes this for FSH)")
LOGIC = {"Library", "PlanDefinition", "Measure", "ActivityDefinition"}
typ = {r["key"]: r["resourceType"] for r in res}
has = collections.defaultdict(bool)
for e in deps:
    if e["origin"] == "ast-export" and e.get("resolved") and typ.get(e["resolved"]) in LOGIC:
        has[e["source"]] = True
print("  W2: logic resources with >=1 edge to another logic resource in this IG")
tot = hit = 0
for t in ("Library", "PlanDefinition", "Measure"):
    keys = [r["key"] for r in res if r["resourceType"] == t]
    n = sum(1 for k in keys if has[k]); tot += len(keys); hit += n
    print(f"    {t:15} {n:4} of {len(keys)}")
print(f"    {'TOTAL':15} {hit:4} of {tot}   (smart-immunizations target: 458 of 458)")
unres = sum(1 for e in deps if not e.get("resolved"))
print(f"  edges with target outside this IG (kept, resolved=null): {unres}")
PY
}

for ig in smart-trust smart-immunizations; do
  run_ig "$ig" && summarise "$ig"
done

if [ "$BYTE" = 1 ]; then
  echo "== byte-identical: stock Publisher vs AstExportCli on smart-trust"
  ref="$WORK/smart-trust-stock"; rm -rf "$ref"; cp -r "$WORK/smart-trust" "$ref"; rm -rf "$ref/output" "$ref/output-ast" "$ref/temp"
  (cd "$ref" && java -Xmx8g -cp "$CP" org.hl7.fhir.igtools.publisher.Publisher -ig . >"$WORK/smart-trust-stock.log" 2>&1)
  n=$(diff -rq "$WORK/smart-trust/output" "$ref/output" | wc -l || true)
  echo "  files differing: $n  (list: diff -rq $WORK/smart-trust/output $ref/output)"
  echo "  build timestamps differ between ANY two runs; a finding is a difference that is not a timestamp."
fi
