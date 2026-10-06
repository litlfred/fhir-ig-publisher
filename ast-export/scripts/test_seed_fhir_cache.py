#!/usr/bin/env python3
"""Offline tests for seed-fhir-cache-from-npm.py: the supply-chain checks from the
review of litlfred/fhir-ig-publisher PR #8 (M7, M8). Every network call is mocked.

    python3 -m unittest discover -s ast-export/scripts -p 'test_*.py'
"""
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("seed", os.path.join(HERE, "seed-fhir-cache-from-npm.py"))
seed = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(seed)


def tgz(name, version):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as t:
        data = json.dumps({"name": name, "version": version}).encode()
        info = tarfile.TarInfo("package/package.json")
        info.size = len(data)
        t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def done(stdout="", returncode=0, stderr=""):
    return subprocess.CompletedProcess([], returncode, stdout=stdout, stderr=stderr)


class TempDirCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        seed._MIRROR = None
        seed.MIRROR_COMMIT = None


class M7NpmPublisher(TempDirCase):
    """M7: the VERSION's publisher must be grahamegrieve; the registry is explicit."""

    def meta(self, npm_user, maintainers=("grahamegrieve <g@example.org>",)):
        return {"version": "1.0.0", "_npmUser": npm_user, "maintainers": list(maintainers),
                "dist": {"tarball": "https://registry.npmjs.org/x/-/x-1.0.0.tgz", "integrity": "sha512-x"}}

    def test_a_maintainer_who_did_not_publish_this_version_is_refused(self):
        with mock.patch.object(seed, "npm_view", return_value=self.meta("someoneelse <s@example.org>")):
            cand, why = seed.resolve("x", "1.0.0")
        self.assertIsNone(cand)
        self.assertIn("someoneelse", why)

    def test_a_version_with_no_recorded_publisher_is_refused(self):
        m = self.meta(None)
        del m["_npmUser"]
        with mock.patch.object(seed, "npm_view", return_value=m):
            cand, _ = seed.resolve("x", "1.0.0")
        self.assertIsNone(cand)

    def test_a_version_grahamegrieve_published_is_accepted_in_either_shape(self):
        for user in ("grahamegrieve <g@example.org>", {"name": "grahamegrieve", "email": "g@example.org"}):
            with mock.patch.object(seed, "npm_view", return_value=self.meta(user, maintainers=())):
                cand, meta = seed.resolve("x", "1.0.0")
            self.assertEqual("x", cand)

    def test_npm_is_asked_at_the_public_registry_explicitly(self):
        with mock.patch.object(seed.subprocess, "run", return_value=done("{}")) as run:
            seed.npm_view("x@1.0.0")
        args = run.call_args[0][0]
        self.assertEqual("https://registry.npmjs.org/", args[args.index("--registry") + 1])


class M8Mirror(TempDirCase):
    """M8: a mirror file must match SHA512SUMS; a git mirror is pinned and its clone checked."""

    def mirror(self, files, sums):
        d = os.path.join(self.tmp, "mirror")
        os.makedirs(d)
        for n, data in files.items():
            with open(os.path.join(d, n), "wb") as f:
                f.write(data)
        if sums is not None:
            with open(os.path.join(d, "SHA512SUMS"), "w") as f:
                f.write("".join(f"{h}  {n}\n" for n, h in sums.items()))
        return d

    def test_a_file_matching_sha512sums_is_accepted(self):
        data = tgz("a.b", "1.0.0")
        d = self.mirror({"a.b#1.0.0.tgz": data}, {"a.b#1.0.0.tgz": hashlib.sha512(data).hexdigest()})
        got, _ = seed.from_mirror(d, "a.b", "1.0.0")
        self.assertIsNotNone(got)

    def test_a_file_not_matching_sha512sums_is_refused(self):
        data = tgz("a.b", "1.0.0")
        d = self.mirror({"a.b#1.0.0.tgz": data}, {"a.b#1.0.0.tgz": "0" * 128})
        got, why = seed.from_mirror(d, "a.b", "1.0.0")
        self.assertIsNone(got)
        self.assertIn("SHA512SUMS", why)

    def test_a_file_missing_from_sha512sums_or_without_one_is_refused(self):
        data = tgz("a.b", "1.0.0")
        self.assertIsNone(seed.from_mirror(self.mirror({"a.b#1.0.0.tgz": data}, {}), "a.b", "1.0.0")[0])
        seed._MIRROR = None
        shutil.rmtree(os.path.join(self.tmp, "mirror"))
        self.assertIsNone(seed.from_mirror(self.mirror({"a.b#1.0.0.tgz": data}, None), "a.b", "1.0.0")[0])

    def test_a_failed_git_mirror_clone_is_reported_not_ignored(self):
        commit = "a" * 40
        calls = []

        def run(cmd, **kw):
            calls.append(cmd)
            if cmd[:2] == ["git", "ls-remote"]:
                return done(f"{commit}\tHEAD\n")
            if "fetch" in cmd:
                return done(returncode=128, stderr="fatal: could not read from remote")
            return done()

        with mock.patch.object(seed.subprocess, "run", side_effect=run):
            got, why = seed.from_mirror("https://example.org/mirror.git", "a.b", "1.0.0")
        self.assertIsNone(got)
        self.assertIn("could not read", why)

    def test_a_git_mirror_is_fetched_at_the_pinned_commit(self):
        pinned = "b" * 40
        seen = []

        def run(cmd, **kw):
            seen.append(cmd)
            if cmd[-2:] == ["rev-parse", "HEAD"]:
                return done(pinned + "\n")
            return done()

        seed.MIRROR_COMMIT = pinned
        with mock.patch.object(seed.subprocess, "run", side_effect=run):
            d, commit, err = seed.mirror_dir("https://example.org/mirror.git")
        self.assertIsNone(err)
        self.assertEqual(pinned, commit)
        self.assertTrue(any("fetch" in c and pinned in c for c in seen), seen)
        self.assertFalse(any(c[:2] == ["git", "ls-remote"] for c in seen), "a given pin is not re-resolved")


class M8Site(TempDirCase):
    """M8: a site file is fetched BY the commit recorded for it."""

    def test_the_file_is_fetched_by_the_recorded_commit(self):
        commit = "c" * 40
        data = tgz("smart.who.int.x", "1.0.0")
        opened = []

        class R:
            def read(self):
                return data

        def urlopen(url, timeout=None):
            opened.append(url)
            return R()

        with mock.patch.object(seed, "head_commit", return_value=commit), \
                mock.patch.object(seed.urllib.request, "urlopen", side_effect=urlopen):
            got, _ = seed.from_site("smart.who.int.x", "1.0.0")
        self.assertEqual(commit, got["commit"])
        self.assertIn(f"/{commit}/", opened[0])
        self.assertEqual(opened[0], got["site"])

    def test_no_commit_no_fetch(self):
        with mock.patch.object(seed, "head_commit", return_value=None), \
                mock.patch.object(seed.urllib.request, "urlopen") as urlopen:
            got, _ = seed.from_site("smart.who.int.x", "1.0.0")
        self.assertIsNone(got)
        urlopen.assert_not_called()


class M8Cache(TempDirCase):
    """M8: a package already in the cache is checked, not accepted unchecked."""

    def cached(self, name, version):
        d = os.path.join(self.tmp, "p")
        os.makedirs(os.path.join(d, "package"))
        json.dump({"name": name, "version": version}, open(os.path.join(d, "package", "package.json"), "w"))
        return d

    def test_a_matching_entry_passes(self):
        self.assertIsNone(seed.check_cached(self.cached("a.b", "1.0.0"), "a.b", "1.0.0"))

    def test_a_mismatched_or_broken_entry_is_refused(self):
        self.assertIsNotNone(seed.check_cached(self.cached("a.b", "2.0.0"), "a.b", "1.0.0"))
        self.assertIsNotNone(seed.check_cached(os.path.join(self.tmp, "absent"), "a.b", "1.0.0"))

    def test_current_is_checked_by_name(self):
        self.assertIsNone(seed.check_cached(self.cached("fhir.base.template", "0.9.0"), "fhir.base.template", "current"))

    def test_main_refuses_a_mismatched_cache_entry(self):
        cache = os.path.join(self.tmp, "cache")
        os.makedirs(os.path.join(cache, "a.b#1.0.0", "package"))
        json.dump({"name": "evil", "version": "1.0.0"},
                  open(os.path.join(cache, "a.b#1.0.0", "package", "package.json"), "w"))
        with mock.patch("sys.stdout", new=io.StringIO()) as out:
            rc = seed.main(["--cache", cache, "--dry-run", "a.b#1.0.0"])
        self.assertEqual(1, rc)
        self.assertIn("MISSING  a.b#1.0.0", out.getvalue())


class Minor(TempDirCase):
    def test_names_that_could_reach_a_path_or_an_npm_flag_are_refused(self):
        for bad in ("-rf", "--registry=evil", "../x", "a/b", "a\\b", "", " a"):
            self.assertFalse(seed.valid_part(bad), bad)
        self.assertTrue(seed.valid_part("hl7.fhir.r4.core"))
        with mock.patch("sys.stdout", new=io.StringIO()) as out, \
                mock.patch.object(seed, "npm_view") as npm:
            rc = seed.main(["--cache", self.tmp, "--dry-run", "--registry=evil#1.0.0", "../../etc#1.0.0"])
        self.assertEqual(1, rc)
        npm.assert_not_called()
        self.assertIn("refused", out.getvalue())

    def test_unpack_renames_into_place_and_leaves_nothing_on_failure(self):
        dest = os.path.join(self.tmp, "cache", "a.b#1.0.0")
        seed.unpack(tgz("a.b", "1.0.0"), dest)
        self.assertTrue(os.path.isfile(os.path.join(dest, "package", "package.json")))
        dest2 = os.path.join(self.tmp, "cache", "c.d#1.0.0")
        with self.assertRaises(Exception):
            seed.unpack(b"not a tarball", dest2)
        self.assertFalse(os.path.exists(dest2))
        self.assertEqual(["a.b#1.0.0"], os.listdir(os.path.join(self.tmp, "cache")), "no temp dir left behind")


if __name__ == "__main__":
    unittest.main()
