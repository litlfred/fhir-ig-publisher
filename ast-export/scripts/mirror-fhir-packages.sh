#!/usr/bin/env bash
# Mirror FHIR packages from packages.fhir.org into a git repository, so an
# environment that can reach GitHub but not packages.fhir.org can seed its
# cache from it (seed-fhir-cache-from-npm.py --mirror <repo>).
#
#   mirror-fhir-packages.sh <mirror-repo-dir> <missing.txt | name#version ...>
#
# Run it on a machine that reaches packages.fhir.org. <missing.txt> is what
# `seed-fhir-cache-from-npm.py --missing-out missing.txt` wrote: the exact
# list the other environment could not get, so nothing is mirrored that is
# not needed. Each file is written as <name>#<version>.tgz after checking that
# its own package/package.json names exactly that package and version; its
# sha512 goes to SHA512SUMS, which seed-fhir-cache-from-npm.py verifies
# every file against. Downloads are https only, redirects included. If the directory is a git repository, the result
# is committed and pushed. The person running this is the trust anchor for
# what it adds.
set -euo pipefail

REPO="${1:?usage: mirror-fhir-packages.sh <mirror-repo-dir> <missing.txt | name#version ...>}"
shift
[ $# -gt 0 ] || { echo "nothing to mirror"; exit 2; }
specs=()
for a in "$@"; do
  if [ -f "$a" ]; then while IFS= read -r l; do [ -n "$l" ] && specs+=("$l"); done < "$a"; else specs+=("$a"); fi
done
mkdir -p "$REPO"
# The identity check, shared by a fresh download and a file already present.
identity() {
  python3 - "$1" <<'PY'
import json, os, sys, tarfile
with tarfile.open(sys.argv[1], "r:gz") as t:
    for m in t.getmembers():
        if os.path.normpath(m.name) == os.path.join("package", "package.json"):
            pj = json.load(t.extractfile(m)); print(f"{pj.get('name')}#{pj.get('version')}"); break
PY
}
# A name or version that starts with '-' or holds a path separator or '..' never reaches a path or curl.
valid() { case "$1" in ""|-*|*/*|*\\*|*..*) return 1;; esac; }
ok=0; bad=0
for spec in "${specs[@]}"; do
  name="${spec%%#*}"; ver="${spec#*#}"
  if [ "$name" = "$spec" ] || ! valid "$name" || ! valid "$ver"; then echo "REFUSED  $spec  (not name#version)"; bad=$((bad+1)); continue; fi
  out="$REPO/$spec.tgz"
  if [ -f "$out" ]; then
    id=$(identity "$out" || true)
    if [ "$id" != "$spec" ]; then echo "REFUSED  $spec  (the file present says $id)"; bad=$((bad+1)); continue; fi
    echo "present  $spec"; ok=$((ok+1)); continue
  fi
  tmp="$(mktemp)"
  got=""
  for base in https://packages.fhir.org https://packages2.fhir.org/packages; do
    if curl -fsSL --proto =https --proto-redir =https -m 300 "$base/$name/$ver" -o "$tmp"; then got="$base"; break; fi
  done
  if [ -z "$got" ]; then echo "MISSING  $spec  (not on packages.fhir.org or packages2)"; bad=$((bad+1)); rm -f "$tmp"; continue; fi
  id=$(identity "$tmp" || true)
  if [ "$id" != "$spec" ]; then echo "REFUSED  $spec  (the tarball says $id)"; bad=$((bad+1)); rm -f "$tmp"; continue; fi
  mv "$tmp" "$out"
  # One line per file: drop an older entry for it, then add this one.
  sums="$REPO/SHA512SUMS"; touch "$sums"
  grep -vF "  $spec.tgz" "$sums" > "$sums.tmp" || true
  (cd "$REPO" && sha512sum "$spec.tgz") >> "$sums.tmp"
  mv "$sums.tmp" "$sums"
  echo "ok       $spec  (from $got)"; ok=$((ok+1))
done
echo "$ok mirrored or present, $bad not."
if git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1; then
  git -C "$REPO" add -A
  git -C "$REPO" commit -qm "Mirror $ok FHIR package(s) from packages.fhir.org" || true
  git -C "$REPO" push -q || echo "push failed: push $REPO yourself"
fi
[ "$bad" -eq 0 ]
