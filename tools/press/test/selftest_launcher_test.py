"""Exercise the selftest's host bind paths and output ownership without a daemon.

Run with python3 -m unittest discover -s tools/press/test -p 'selftest_launcher_test.py'.
The affected fault scope is output resolution, bind syntax, and cleanup ownership.
The fake Docker runs only the launcher's orchestration: browser/solver behavior is
proved by the real offline selftest. Every Docker call must bind existing absolute
host paths, including when the caller supplies a relative output directory.
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


TOOL = Path(__file__).resolve().parents[1]
FAKE_DOCKER = r"""#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
if args[:2] == ["image", "inspect"]:
    sys.exit(0)
mounts = {}
invalid = []
for i, arg in enumerate(args):
    if arg == "--mount":
        fields = dict(field.split("=", 1) for field in args[i + 1].split(",") if "=" in field)
        source, target = fields.get("src", ""), fields.get("dst", "")
    elif arg == "-v":
        fields = args[i + 1].split(":")
        source, target = fields[0], fields[1] if len(fields) > 1 else ""
        if len(fields) not in (2, 3) or not target.startswith("/"):
            invalid.append("ambiguous volume: " + args[i + 1])
    else:
        continue
    if not Path(source).is_absolute() or not Path(source).exists():
        invalid.append("bind source is not an existing absolute path: " + source)
    mounts[target] = source
with open(os.environ["FAKE_DOCKER_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps({"args": args, "mounts": mounts, "invalid": invalid}) + "\n")
if invalid:
    print("fake Docker rejected bind: " + "; ".join(invalid), file=sys.stderr)
    sys.exit(125)
if "/work/fixtures/build_fixtures.py" in args:
    for name in ("fixture_bad.html", "fixture_good.html", "paged_bad.html", "paged_good.html"):
        Path(mounts["/fx"], name).write_text("<!doctype html><title>fixture</title>", encoding="utf-8")
if "deck_pdf" in args and any(arg.endswith("/paged_bad.html") for arg in args):
    sys.exit(1)
sys.exit(0)
"""


class SelftestLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="press-selftest-launcher-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tool = self.root / "tool"
        (self.tool / "bin").mkdir(parents=True)
        (self.tool / "fixtures").mkdir()
        shutil.copy(TOOL / "bin" / "press", self.tool / "bin" / "press")
        for kind in ("fig", "paged", "chart"):
            stub = self.tool / "fixtures" / f"{kind}_launcher_selftest.sh"
            stub.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            stub.chmod(0o755)
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir()
        docker = fake_bin / "docker"
        docker.write_text(FAKE_DOCKER, encoding="utf-8")
        docker.chmod(0o755)
        self.log = self.root / "docker.jsonl"
        self.env = {
            **os.environ,
            "PATH": f"{fake_bin}:{os.environ['PATH']}",
            "PRESS_IMAGE": "press-selftest-double",
            "FAKE_DOCKER_LOG": str(self.log),
        }
        self.env.pop("PRESS_HOUSE", None)
        (self.root / "work").mkdir()

    def run_selftest(self, output):
        return subprocess.run(
            ["bash", str(self.tool / "bin" / "press"), "selftest", str(output)],
            cwd=self.root / "work",
            env=self.env,
            capture_output=True,
            text=True,
            timeout=20,
        )

    def assert_clean_binds(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = [json.loads(line) for line in self.log.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(calls), 11, "all direct and QA/PDF selftest Docker runs execute")
        self.assertEqual(
            [issue for call in calls for issue in call["invalid"]],
            [],
            "every selftest bind source is an existing absolute path",
        )

    def test_relative_output_binds_absolute_paths(self):
        self.assert_clean_binds(self.run_selftest("relative-output"))

    def test_relative_space_and_colon_output_binds_unambiguously(self):
        self.assert_clean_binds(self.run_selftest("output:a b"))

    def test_existing_empty_relative_output(self):
        (self.root / "work" / "empty").mkdir()
        self.assert_clean_binds(self.run_selftest("empty"))

    def test_comma_output_is_refused_without_deletion(self):
        output = self.root / "work" / "a,b"
        output.mkdir()
        (output / ".press-selftest").touch()
        sentinel = output / "keep"
        sentinel.touch()
        result = self.run_selftest("a,b")
        self.assertEqual(result.returncode, 6)
        self.assertIn("comma", result.stdout)
        self.assertTrue(sentinel.exists())
        self.assertFalse(self.log.exists())

    def test_symlink_output_is_refused_without_deletion(self):
        output = self.root / "real-output"
        output.mkdir()
        (output / ".press-selftest").touch()
        sentinel = output / "keep"
        sentinel.touch()
        (self.root / "work" / "linked").symlink_to(output, target_is_directory=True)
        result = self.run_selftest("linked")
        self.assertEqual(result.returncode, 6)
        self.assertIn("symlink", result.stdout)
        self.assertTrue(sentinel.exists())
        self.assertFalse(self.log.exists())

    def test_absolute_output_control(self):
        self.assert_clean_binds(self.run_selftest(self.root / "absolute-output"))

    def test_owned_output_is_recreated(self):
        output = self.root / "work" / "owned"
        output.mkdir()
        (output / ".press-selftest").touch()
        (output / "stale").write_text("stale", encoding="utf-8")
        self.assert_clean_binds(self.run_selftest("owned"))
        self.assertFalse((output / "stale").exists())
        self.assertTrue((output / ".press-selftest").is_file())

    def test_unowned_nonempty_output_is_preserved(self):
        output = self.root / "work" / "unowned"
        output.mkdir()
        sentinel = output / "keep"
        sentinel.write_text("must remain", encoding="utf-8")
        result = self.run_selftest("unowned")
        self.assertEqual(result.returncode, 6, result.stdout + result.stderr)
        self.assertIn("no .press-selftest marker", result.stdout)
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "must remain")
        self.assertFalse(self.log.exists(), "no containers run against refused output")


if __name__ == "__main__":
    unittest.main()
