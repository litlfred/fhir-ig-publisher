#!/usr/bin/env python3
"""
Seed the FHIR package cache from npm, EXACT versions only.

    seed-fhir-cache-from-npm.py [--cache DIR] [--sushi-config FILE] [--mirror DIR|GIT-URL]
                                [--mirror-commit SHA] [--missing-out FILE] [--template-repo NAME=OWNER/REPO]
                                [--dry-run] [name#version ...]

Sources, tried in this order for each package; each is a TRUST ANCHOR named
by the owner or the package's own publisher, and nothing else is consulted:

1. the cache itself (already present);
2. npm, account `grahamegrieve` (owner, 2026-09-30: trusted);
3. the publisher's own published-site repository on GitHub:
   WorldHealthOrganization/smart-html for `smart.who.int.*`,
   IHE/publications for `ihe.*`;
4. a template's own source repository, at HEAD (`current` means HEAD; the
   commit is recorded). The repository comes from FHIR/ig-registry's
   templates.json, read LIVE every run, or from an explicit
   `--template-repo name=owner/repo` for a template the registry does not list
   (who.template.root, 2026-10-01). Owner: fhir.base.template is trusted;
5. `--mirror`: a directory or git repository of `<name>#<version>.tgz` the
   owner filled from packages.fhir.org (see mirror-fhir-packages.sh).

Sources 3 to 5 publish no integrity hash, so the tarball's own
`package/package.json` must name exactly the requested package and version,
and its sha512 is recorded.

For an environment that reaches registry.npmjs.org but not packages.fhir.org.
The owner ruled (2026-09-30) that the npm account `grahamegrieve` is a trusted
publisher of FHIR packages: Grahame Grieve founded HL7 FHIR and maintains the
IG Publisher. That account is the trust anchor here, and nothing else is.

Rules, each one a refusal rather than a best effort:

- EXACT versions only. A pinned `hl7.fhir.uv.cql#1.0.0` is never satisfied by
  2.0.0: a different version is different content, and a build over it would
  measure something else while looking like the real thing.
- A tarball is accepted only when THAT VERSION was published by `grahamegrieve`
  (its `_npmUser`), not merely when he is among the package's maintainers: a
  co-maintainer can publish a version. npm is always asked at
  https://registry.npmjs.org/, explicitly, whatever the local npm config says.
  npm's `0.0.1-security` placeholder (a package taken down as malicious) is
  refused by name.
- A mirror file must match the mirror's own `SHA512SUMS`; a git mirror is
  cloned at a recorded commit, and a failed clone is reported. A site file is
  fetched BY the commit recorded for it. A package already in the cache must
  name exactly the package and version asked for in its `package.json`.
- A package name or version that starts with `-` or holds a path separator or
  `..` is refused before it reaches a path or an npm argument.
- A package is unpacked in a temporary directory beside its destination and
  renamed into place, so an interrupted run never leaves a half package.
- The tarball's published `dist.integrity` (sha512) is verified before it is
  unpacked.
- The unscoped name is tried first, then `@hl7/<name>`: the core packages live
  under the scope, because the unscoped `hl7.fhir.r4.core` is a placeholder.
- Dependencies are followed through each package's own `package.json`,
  exact versions only. A patch wildcard (`1.1.x`) is resolved as the IG
  Publisher resolves it, to the highest `1.1.N` a source LISTS on this run,
  and the resolution is recorded. `dev` and other ranges are reported, never
  guessed.

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
import atexit
import shutil
import tempfile

TRUSTED = "grahamegrieve"
PLACEHOLDER = "0.0.1-security"
NPM_REGISTRY = "https://registry.npmjs.org/"


def valid_part(s):
    """A package name or version safe to put in a path or an npm argument."""
    return (isinstance(s, str) and s != "" and not s.startswith("-") and "/" not in s and "\\" not in s
            and ".." not in s and "\0" not in s and s.strip() == s)


def npm_view(spec):
    r = subprocess.run(["npm", "view", spec, "--json", "--registry", NPM_REGISTRY], capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        d = json.loads(r.stdout)
    except json.JSONDecodeError:
        return None
    return d[-1] if isinstance(d, list) else d


def _person(m):
    """npm prints a person as 'name <email>'; the registry document holds {name, email}."""
    if isinstance(m, dict):
        return m.get("name")
    return str(m).split(" ")[0] if m else None


def maintainers(meta):
    return [_person(m) for m in meta.get("maintainers") or []]


def publisher(meta):
    """Who published THIS version: npm's `_npmUser`, or None when it is not recorded."""
    return _person(meta.get("_npmUser"))


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
        who = publisher(meta)
        if who != TRUSTED:
            reasons.append(f"{cand}@{version}: published by {who!r}, not {TRUSTED}")
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
    """Unpack into a temporary directory beside `dest`, then rename it into place."""
    parent = os.path.dirname(os.path.abspath(dest))
    os.makedirs(parent, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix=".unpack-", dir=parent)
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
            for m in t.getmembers():
                p = os.path.normpath(m.name)
                if p.startswith("..") or os.path.isabs(p):
                    raise ValueError(f"unsafe path in tarball: {m.name}")
            t.extractall(tmp, filter="data")
        os.rename(tmp, dest)
    except BaseException:
        shutil.rmtree(tmp, ignore_errors=True)
        raise


SITE_REPOS = [
    # (name prefix, owner/repo, branch, path builder)
    ("smart.who.int.", "WorldHealthOrganization/smart-html", "main",
     lambda name, v, _ihe: f"{name[len('smart.who.int.'):]}/{v}/package.tgz"),
    ("ihe.", "IHE/publications", "master",
     lambda name, v, ihe: (f"{ihe[name]}/{v}/package.tgz" if name in ihe else None)),
]

# Nothing below is computed once and kept. Every lookup is made live, per run,
# in a scratch directory deleted at exit (owner, 2026-10-01: "dynamically load
# from repos... dont calc once and assume fixed. avoid drift").
WORK = tempfile.mkdtemp(prefix="ast-export-seed-")
atexit.register(lambda: shutil.rmtree(WORK, ignore_errors=True))

TEMPLATE_REGISTRY = "https://raw.githubusercontent.com/FHIR/ig-registry/master/templates.json"
TEMPLATE_OVERRIDES = {}  # --template-repo name=owner/repo, for a template the registry does not list
_TEMPLATES = None
_IHE = None
_IHE_VERSIONS = {}


def template_repos():
    """FHIR/ig-registry's templates.json, "the authoritative GitHub repositories for known
    templates", read live, plus the caller's explicit --template-repo entries."""
    global _TEMPLATES
    if _TEMPLATES is None:
        try:
            reg = json.load(urllib.request.urlopen(TEMPLATE_REGISTRY, timeout=60))
            _TEMPLATES = {k: (v, "ig-registry") for k, v in reg.items() if k != "explanation"}
        except Exception:  # noqa: BLE001 — reported through the missing list
            _TEMPLATES = {}
        for k, v in TEMPLATE_OVERRIDES.items():
            _TEMPLATES[k] = (v, "--template-repo")
    return _TEMPLATES


def ihe_paths():
    """ihe.<domain>.<profile> -> '<DOMAIN>/<Profile>' as IHE/publications spells the folders (case varies: mCSD)."""
    global _IHE
    if _IHE is not None:
        return _IHE
    _IHE = {}
    tmp = os.path.join(WORK, "ihe-tree")
    r = subprocess.run(["git", "clone", "-q", "--depth", "1", "--filter=blob:none", "--no-checkout",
                        "https://github.com/IHE/publications", tmp], capture_output=True, text=True)
    if r.returncode != 0:
        return _IHE
    r = subprocess.run(["git", "-C", tmp, "ls-tree", "-d", "--name-only", "HEAD"], capture_output=True, text=True)
    for dom in r.stdout.split():
        r2 = subprocess.run(["git", "-C", tmp, "ls-tree", "-d", "--name-only", f"HEAD:{dom}"],
                            capture_output=True, text=True)
        for prof in r2.stdout.split():
            _IHE[f"ihe.{dom.lower()}.{prof.lower()}"] = f"{dom}/{prof}"
            r3 = subprocess.run(["git", "-C", tmp, "ls-tree", "-d", "--name-only", f"HEAD:{dom}/{prof}"],
                                capture_output=True, text=True)
            _IHE_VERSIONS[f"ihe.{dom.lower()}.{prof.lower()}"] = r3.stdout.split()
    return _IHE


def resolve_patch_wildcard(name, version):
    """'1.1.x' -> the highest '1.1.N' the live sources hold, as the IG Publisher resolves it.
    Returns (concrete, where) or (None, reason). Never guesses beyond what a source lists."""
    m = re.fullmatch(r"(\d+)\.(\d+)\.x", version)
    if not m:
        return None, f"'{version}' is not an exact version or a patch wildcard"
    cands = []
    if name.startswith("ihe."):
        ihe_paths()
        cands += [(v, "IHE/publications") for v in _IHE_VERSIONS.get(name, [])]
    vs = npm_view(f"{name}") or {}
    cands += [(v, "npm") for v in (vs.get("versions") or []) if isinstance(vs.get("versions"), list)]
    pat = re.compile(rf"{m.group(1)}\.{m.group(2)}\.(\d+)")
    best = max(((int(pat.fullmatch(v).group(1)), v, w) for v, w in cands if pat.fullmatch(v)), default=None)
    if best is None:
        return None, f"no {m.group(1)}.{m.group(2)}.N listed by any source"
    return best[1], best[2]


def head_commit(repo, branch="HEAD"):
    return remote_commit(f"https://github.com/{repo}", branch)


def remote_commit(url, ref="HEAD"):
    """The commit `ref` names in `url` NOW, or None. Looked up live, every run."""
    r = subprocess.run(["git", "ls-remote", url, ref], capture_output=True, text=True)
    out = r.stdout.split() if r.returncode == 0 else []
    return out[0] if out and re.fullmatch(r"[0-9a-f]{40}", out[0]) else None


def tar_identity(data):
    """(name, version) from a package tarball's package/package.json."""
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
        for m in t.getmembers():
            if os.path.normpath(m.name) == os.path.join("package", "package.json"):
                pj = json.load(t.extractfile(m))
                return pj.get("name"), pj.get("version")
    return None, None


def sha512(data):
    return "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()


def from_site(name, version):
    """(provenance, bytes) from the publisher's own site repo, or (None, reason)."""
    for prefix, repo, branch, path in SITE_REPOS:
        if not name.startswith(prefix):
            continue
        rel = path(name, version, ihe_paths() if prefix == "ihe." else {})
        if rel is None:
            return None, f"{repo}: no folder for {name}"
        # The commit is resolved FIRST and the file fetched BY it, so the
        # recorded commit is the one the bytes came from.
        commit = head_commit(repo, branch)
        if commit is None:
            return None, f"{repo}: cannot resolve {branch} to a commit"
        url = f"https://raw.githubusercontent.com/{repo}/{commit}/{rel}"
        try:
            data = urllib.request.urlopen(url, timeout=300).read()
        except Exception as e:  # noqa: BLE001
            return None, f"{url}: {e}"
        n, v = tar_identity(data)
        if (n, v) != (name, version):
            return None, f"{url}: package.json says {n}#{v}"
        return {"site": url, "branch": branch, "commit": commit, "sha512": sha512(data)}, data
    return None, "no publisher site repo for this name"


def from_template_repo(name, version, dest):
    """Clone a template's repo at HEAD into the cache layout. 'current' means HEAD."""
    entry = template_repos().get(name)
    if not entry:
        return None, "not in FHIR/ig-registry templates.json and no --template-repo given"
    repo, via = entry
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo or ""):
        return None, f"{repo!r} is not owner/repo"
    tmp = os.path.join(WORK, "template-" + re.sub(r"[^A-Za-z0-9._-]", "_", name))
    shutil.rmtree(tmp, ignore_errors=True)
    if subprocess.run(["git", "clone", "-q", "--depth", "1", f"https://github.com/{repo}", tmp],
                      capture_output=True).returncode != 0:
        return None, f"{repo}: clone failed"
    pjf = os.path.join(tmp, "package", "package.json")
    if not os.path.isfile(pjf):
        return None, f"{repo} HEAD has no package/package.json"
    pj = json.load(open(pjf))
    if pj.get("name") != name or (version != "current" and pj.get("version") != version):
        return None, f"{repo} HEAD is {pj.get('name')}#{pj.get('version')}, not {name}#{version}"
    commit = subprocess.run(["git", "-C", tmp, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    # Assemble beside dest, then rename into place.
    parent = os.path.dirname(os.path.abspath(dest))
    os.makedirs(parent, exist_ok=True)
    stage = tempfile.mkdtemp(prefix=".unpack-", dir=parent)
    pkg = os.path.join(stage, "package")
    os.makedirs(pkg)
    for e in os.listdir(tmp):
        if e in (".git", "package"):
            continue
        os.rename(os.path.join(tmp, e), os.path.join(pkg, e))
    for e in os.listdir(os.path.join(tmp, "package")):
        os.rename(os.path.join(tmp, "package", e), os.path.join(pkg, e))
    os.rename(stage, dest)
    return {"templateRepo": f"https://github.com/{repo}", "repoFrom": via, "commit": commit,
            "headVersion": pj.get("version")}, None


_MIRROR = None  # (directory, commit or None, error or None), for THIS run only
MIRROR_COMMIT = None  # --mirror-commit: pin a git mirror to this commit


def mirror_dir(spec):
    """(directory, commit, error). A git mirror is cloned at a commit fixed BEFORE the clone:
    --mirror-commit, or the remote HEAD looked up live; the clone's exit code is checked
    and its checked-out commit compared with that one."""
    global _MIRROR
    if _MIRROR is None and spec:
        if os.path.isdir(spec):
            _MIRROR = (spec, None, None)
        else:
            commit = MIRROR_COMMIT or remote_commit(spec)
            d = os.path.join(WORK, "mirror")
            if commit is None:
                _MIRROR = (None, None, f"{spec}: cannot resolve HEAD to a commit")
            else:
                steps = [["git", "init", "-q", d],
                         ["git", "-C", d, "fetch", "-q", "--depth", "1", spec, commit],
                         ["git", "-C", d, "checkout", "-q", "--detach", "FETCH_HEAD"]]
                err = None
                for cmd in steps:
                    r = subprocess.run(cmd, capture_output=True, text=True)
                    if r.returncode != 0:
                        err = f"{spec}@{commit}: {' '.join(cmd[3:5])} failed: {r.stderr.strip()}"
                        break
                if err is None:
                    got = subprocess.run(["git", "-C", d, "rev-parse", "HEAD"], capture_output=True,
                                         text=True).stdout.strip()
                    if got != commit:
                        err = f"{spec}: checked out {got}, not {commit}"
                _MIRROR = (None, None, err) if err else (d, commit, None)
    return _MIRROR or (None, None, None)


def read_sha512sums(d):
    """{file: hex} from the mirror's SHA512SUMS (sha512sum's format), or None when absent."""
    f = os.path.join(d, "SHA512SUMS")
    if not os.path.isfile(f):
        return None
    out = {}
    with open(f, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    for line in lines:
        m = re.fullmatch(r"([0-9a-f]{128}) [ *](.+)", line)
        if m:
            out[m.group(2)] = m.group(1)
    return out


def from_mirror(spec, name, version):
    d, commit, err = mirror_dir(spec)
    if err:
        return None, err
    if not d:
        return None, "no --mirror given"
    base = f"{name}#{version}.tgz"
    f = os.path.join(d, base)
    if not os.path.exists(f):
        return None, f"not in mirror {spec}"
    with open(f, "rb") as fh:
        data = fh.read()
    sums = read_sha512sums(d)
    if sums is None:
        return None, f"mirror {spec} has no SHA512SUMS"
    if base not in sums:
        return None, f"{base} is not listed in the mirror's SHA512SUMS"
    if hashlib.sha512(data).hexdigest() != sums[base]:
        return None, f"{base} does not match the mirror's SHA512SUMS"
    n, v = tar_identity(data)
    if (n, v) != (name, version):
        return None, f"mirror file says {n}#{v}"
    return {"mirror": spec, "commit": commit, "file": base, "sha512": sha512(data)}, data


def check_cached(dest, name, version):
    """None when `dest` holds exactly name#version, else why not. 'current' (a template at HEAD)
    is checked by name only: its package.json carries the template's own version."""
    pjf = os.path.join(dest, "package", "package.json")
    try:
        with open(pjf) as fh:
            pj = json.load(fh)
    except (OSError, ValueError) as e:
        return f"{pjf}: {e}"
    if pj.get("name") != name or (version != "current" and pj.get("version") != version):
        return f"cache entry {dest} says {pj.get('name')}#{pj.get('version')}"
    return None


def _pj_bytes(data):
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
        for m in t.getmembers():
            if os.path.normpath(m.name) == os.path.join("package", "package.json"):
                return t.extractfile(m).read()
    return b"{}"


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
    global MIRROR_COMMIT
    cache = os.path.expanduser("~/.fhir/packages")
    dry = False
    mirror = None
    missing_out = None
    wanted = []
    it = iter(argv)
    for a in it:
        if a == "--cache":
            cache = next(it)
        elif a == "--sushi-config":
            wanted += sushi_deps(next(it))
        elif a == "--dry-run":
            dry = True
        elif a == "--mirror":
            mirror = next(it)
        elif a == "--mirror-commit":
            MIRROR_COMMIT = next(it)
        elif a == "--missing-out":
            missing_out = next(it)
        elif a == "--template-repo":
            k, _, v = next(it).partition("=")
            TEMPLATE_OVERRIDES[k] = v
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
        if not (valid_part(name) and valid_part(version)):
            missing.append((spec, "refused: a name or version must not start with '-' or hold a path separator or '..'"))
            continue
        if version.endswith(".x"):
            concrete, where = resolve_patch_wildcard(name, version)
            if concrete is None:
                missing.append((spec, where))
                continue
            prov.setdefault("wildcards", {})[spec] = {"resolved": concrete, "from": where}
            installed.append((spec, f"wildcard -> {concrete} (highest patch at {where})"))
            queue.append(f"{name}#{concrete}")
            continue
        dest = os.path.join(cache, spec)
        if os.path.exists(dest):
            bad = check_cached(dest, name, version)
            if bad:
                missing.append((spec, f"refused: {bad}; remove it and run again"))
                continue
            pj = json.load(open(os.path.join(dest, "package", "package.json")))
            installed.append((spec, "already in cache"))
        else:
            pj = None
            reasons = []
            # 2. npm, trusted account
            if exact(version):
                cand, meta = resolve(name, version)
                if cand is not None:
                    if dry:
                        installed.append((spec, f"would install from npm {cand}"))
                        pj = {"dependencies": meta.get("dependencies") or {}}
                    else:
                        try:
                            url, integrity, data = fetch_verified(meta)
                            unpack(data, dest)
                            prov["packages"][spec] = {"source": "npm", "npm": cand, "tarball": url,
                                                      "integrity": integrity, "publishedBy": publisher(meta),
                                                      "maintainers": maintainers(meta)}
                            installed.append((spec, f"npm {cand}"))
                            pj = json.load(open(os.path.join(dest, "package", "package.json")))
                        except Exception as e:  # noqa: BLE001
                            reasons.append(f"npm: {e}")
                else:
                    reasons.append(f"npm: {meta}")
            else:
                reasons.append(f"npm: '{version}' is not an exact version")
            # 3. the publisher's own site repo; 5. the owner's mirror
            for label, fetch in (("site", lambda: from_site(name, version)),
                                 ("mirror", lambda: from_mirror(mirror, name, version))):
                if pj is not None or not exact(version):
                    break
                got, data = fetch()
                if got is None:
                    reasons.append(f"{label}: {data}")
                    continue
                if dry:
                    installed.append((spec, f"would install from {label}"))
                    pj = json.load(io.BytesIO(_pj_bytes(data)))
                else:
                    unpack(data, dest)
                    prov["packages"][spec] = {"source": label, **got}
                    installed.append((spec, label))
                    pj = json.load(open(os.path.join(dest, "package", "package.json")))
            # 4. a template's own repo ('current' = HEAD)
            if pj is None and name in template_repos():
                if dry:
                    installed.append((spec, f"would clone {template_repos()[name][0]}"))
                    pj = {}
                else:
                    got, err = from_template_repo(name, version, dest)
                    if got is None:
                        reasons.append(f"template: {err}")
                    else:
                        prov["packages"][spec] = {"source": "template-repo", **got}
                        installed.append((spec, f"template repo {got['commit'][:8]}"))
                        pj = json.load(open(os.path.join(dest, "package", "package.json")))
            if pj is None:
                missing.append((spec, " | ".join(reasons)))
                continue
        for dn, dv in (pj.get("dependencies") or {}).items():
            queue.append(f"{dn}#{dv}")

    if not dry:
        os.makedirs(cache, exist_ok=True)
        json.dump(prov, open(prov_path, "w"), indent=2, sort_keys=True)
    for s, how in installed:
        print(f"ok       {s}  ({how})")
    for s, why in missing:
        print(f"MISSING  {s}  — {why}")
    if missing_out:
        with open(missing_out, "w") as f:
            f.write("".join(f"{sp}\n" for sp, _ in missing))
    print(f"\n{len(installed)} installed or present, {len(missing)} missing. Cache: {cache}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
