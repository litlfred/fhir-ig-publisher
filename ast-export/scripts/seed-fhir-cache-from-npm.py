#!/usr/bin/env python3
"""
Seed the FHIR package cache from npm, EXACT versions only.

    seed-fhir-cache-from-npm.py [--cache DIR] [--sushi-config FILE] [--dry-run] [name#version ...]

For an environment that reaches registry.npmjs.org but not packages.fhir.org.
The owner ruled (2026-09-30) that the npm account `grahamegrieve` is a trusted
publisher of FHIR packages: Grahame Grieve founded HL7 FHIR and maintains the
IG Publisher. That account is the trust anchor here, and nothing else is.

Rules, each one a refusal rather than a best effort:

- EXACT versions only. A pinned `hl7.fhir.uv.cql#1.0.0` is never satisfied by
  2.0.0: a different version is different content, and a build over it would
  measure something else while looking like the real thing.
- A tarball is accepted only when its npm maintainers include `grahamegrieve`.
  npm's `0.0.1-security` placeholder (a package taken down as malicious) is
  refused by name.
- The tarball's published `dist.integrity` (sha512) is verified before it is
  unpacked.
- The unscoped name is tried first, then `@hl7/<name>`: the core packages live
  under the scope, because the unscoped `hl7.fhir.r4.core` is a placeholder.
- Dependencies are followed through each package's own `package.json`,
  exact versions only. `current`, `dev` and ranges are reported as
  unresolvable, never guessed.

Writes `<cache>/<name>#<version>/package/...`, the layout SUSHI and the IG
Publisher read, and `<cache>/ast-export-npm-provenance.json` with where every
package came from. Exits 0 when everything asked for is installed, 1 when
anything is missing, and 2 on a usage error. What is missing is always listed.
"""
import base64
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import urllib.request

TRUSTED = "grahamegrieve"
PLACEHOLDER = "0.0.1-security"


def npm_view(spec):
    r = subprocess.run(["npm", "view", spec, "--json"], capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        d = json.loads(r.stdout)
    except json.JSONDecodeError:
        return None
    return d[-1] if isinstance(d, list) else d


def maintainers(meta):
    out = []
    for m in meta.get("maintainers") or []:
        out.append(m.get("name") if isinstance(m, dict) else str(m).split(" ")[0])
    return out


def resolve(name, version):
    """(spec, meta) for an exact trusted match, or (None, reason)."""
    reasons = []
    for cand in (name, "@hl7/" + name):
        meta = npm_view(f"{cand}@{version}")
        if meta is None:
            reasons.append(f"{cand}@{version}: not on npm")
            continue
        if meta.get("version") != version:
            reasons.append(f"{cand}: resolved to {meta.get('version')}, not {version}")
            continue
        if version == PLACEHOLDER:
            reasons.append(f"{cand}: npm malicious-package placeholder")
            continue
        who = maintainers(meta)
        if TRUSTED not in who:
            reasons.append(f"{cand}@{version}: maintainers {who}, not {TRUSTED}")
            continue
        return cand, meta
    return None, "; ".join(reasons)


def fetch_verified(meta):
    url = meta["dist"]["tarball"]
    data = urllib.request.urlopen(url, timeout=300).read()
    integrity = meta["dist"].get("integrity", "")
    if not integrity.startswith("sha512-"):
        raise ValueError(f"{url}: no sha512 integrity published")
    got = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
    if got != integrity:
        raise ValueError(f"{url}: integrity mismatch")
    return url, integrity, data


def unpack(data, dest):
    os.makedirs(dest, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
        for m in t.getmembers():
            p = os.path.normpath(m.name)
            if p.startswith("..") or os.path.isabs(p):
                raise ValueError(f"unsafe path in tarball: {m.name}")
        t.extractall(dest, filter="data")


def exact(v):
    return isinstance(v, str) and re.fullmatch(r"\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?", v) is not None


def sushi_deps(path):
    """name#version from a sushi-config.yaml's `dependencies:` and `fhirVersion:`, without a YAML library."""
    out = []
    lines = open(path, encoding="utf-8").read().splitlines()
    fv = next((l.split(":", 1)[1].split("#")[0].strip() for l in lines if l.startswith("fhirVersion:")), None)
    if fv:
        major = {"4.0.1": "r4", "4.3.0": "r4b", "5.0.0": "r5"}.get(fv)
        if major:
            out.append(f"hl7.fhir.{major}.core#{fv}")
    i = next((k for k, l in enumerate(lines) if l.startswith("dependencies:")), None)
    if i is None:
        return out
    name = None
    for l in lines[i + 1:]:
        if l and not l.startswith(" "):
            break
        m = re.match(r"^  ([A-Za-z0-9._-]+):\s*(.*)$", l)
        if m:
            name, rest = m.group(1), m.group(2).split("#")[0].strip()
            if rest:
                out.append(f"{name}#{rest}")
            continue
        m = re.match(r"^\s+version:\s*(\S+)", l)
        if m and name:
            out.append(f"{name}#{m.group(1)}")
    return out


def main(argv):
    cache = os.path.expanduser("~/.fhir/packages")
    dry = False
    wanted = []
    it = iter(argv)
    for a in it:
        if a == "--cache":
            cache = next(it)
        elif a == "--sushi-config":
            wanted += sushi_deps(next(it))
        elif a == "--dry-run":
            dry = True
        elif "#" in a:
            wanted.append(a)
        else:
            print(__doc__)
            return 2
    if not wanted:
        print(__doc__)
        return 2

    prov_path = os.path.join(cache, "ast-export-npm-provenance.json")
    prov = json.load(open(prov_path)) if os.path.exists(prov_path) else {"trustAnchor": TRUSTED, "packages": {}}
    installed, missing, seen = [], [], set()
    queue = list(wanted)
    while queue:
        spec = queue.pop(0)
        if spec in seen:
            continue
        seen.add(spec)
        name, version = spec.split("#", 1)
        dest = os.path.join(cache, spec)
        if os.path.exists(os.path.join(dest, "package", "package.json")):
            pj = json.load(open(os.path.join(dest, "package", "package.json")))
            installed.append((spec, "already in cache"))
        else:
            if not exact(version):
                missing.append((spec, f"'{version}' is not an exact version; not guessed"))
                continue
            cand, meta = resolve(name, version)
            if cand is None:
                missing.append((spec, meta))
                continue
            if dry:
                installed.append((spec, f"would install from {cand}"))
                pj = {"dependencies": meta.get("dependencies") or {}}
            else:
                try:
                    url, integrity, data = fetch_verified(meta)
                    unpack(data, dest)
                except Exception as e:  # noqa: BLE001 — reported, never swallowed
                    missing.append((spec, str(e)))
                    continue
                prov["packages"][spec] = {"npm": cand, "tarball": url, "integrity": integrity,
                                          "maintainers": maintainers(meta)}
                installed.append((spec, f"from {cand}"))
                pj = json.load(open(os.path.join(dest, "package", "package.json")))
        for dn, dv in (pj.get("dependencies") or {}).items():
            queue.append(f"{dn}#{dv}")

    if not dry:
        os.makedirs(cache, exist_ok=True)
        json.dump(prov, open(prov_path, "w"), indent=2, sort_keys=True)
    for s, how in installed:
        print(f"ok       {s}  ({how})")
    for s, why in missing:
        print(f"MISSING  {s}  — {why}")
    print(f"\n{len(installed)} installed or present, {len(missing)} missing. Cache: {cache}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
