"""Check the actual CI packer preserves filenames rejected by artifact upload."""

from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest


PACKER = Path(__file__).resolve().parents[1] / "bin" / "pack-evidence.sh"
WORKFLOW = Path(__file__).resolve().parents[3] / ".github" / "workflows" / "press.yml"


class EvidenceArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="press-evidence-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.evidence = self.root / "evidence"
        self.archive = self.root / "evidence.tar.gz"

    def pack(self, *args):
        return subprocess.run(
            ["bash", str(PACKER), *map(str, args)],
            capture_output=True,
            text=True,
            timeout=10,
        )

    def test_portable_archive_preserves_boundary_names_hidden_files_and_bytes(self):
        self.evidence.mkdir()
        expected = {
            "chart-launcher/a:b c/check.json": b'{"ok":true}\n',
            'odd*?"<>|name.txt': b"boundary fixture\x00\xff",
            ".press-selftest": b"",
            "paged/report.json": b"{}\n",
        }
        for name, data in expected.items():
            file = self.evidence / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
        result = self.pack(self.evidence, self.archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.archive.is_file(), "the packer must produce the upload file")
        self.assertEqual(self.archive.read_bytes()[:2], b"\x1f\x8b", "upload must be gzip data")
        with tarfile.open(self.archive, "r:gz") as archive:
            actual = {
                member.name.removeprefix("./"): archive.extractfile(member).read()
                for member in archive.getmembers()
                if member.isfile()
            }
        self.assertEqual(
            actual, expected, "all boundary evidence must retain its original path and bytes"
        )
        self.assertTrue(all(c not in self.archive.name for c in '":<>|*?\r\n'))

    def test_missing_directory_leaves_separate_failure_log_available(self):
        log = self.root / "ci-selftest.log"
        log.write_text("startup refused\n", encoding="utf-8")
        result = self.pack(self.evidence, self.archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.archive.exists())
        self.assertEqual(log.read_text(encoding="utf-8"), "startup refused\n")

    def test_non_directory_evidence_is_refused(self):
        self.evidence.write_text("keep", encoding="utf-8")
        self.assertEqual(self.pack(self.evidence, self.archive).returncode, 2)
        self.assertEqual(self.evidence.read_text(encoding="utf-8"), "keep")
        self.assertFalse(self.archive.exists())

    def test_unwritable_archive_parent_fails_instead_of_claiming_evidence(self):
        self.evidence.mkdir()
        result = self.pack(self.evidence, self.root / "absent" / "archive.tar.gz")
        self.assertNotEqual(result.returncode, 0)

    def test_missing_arguments_are_refused(self):
        self.assertEqual(self.pack(self.evidence).returncode, 2)

    def test_workflow_uploads_packaged_evidence_and_the_separate_failure_log(self):
        lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
        commands = [
            line.strip().removeprefix("run: ")
            for line in lines
            if line.strip().startswith("run: bash tools/press/bin/pack-evidence.sh ")
        ]
        self.assertEqual(len(commands), 1, "CI must execute the maintained evidence packer")
        tool = self.root / "tools" / "press" / "bin"
        tool.mkdir(parents=True)
        shutil.copy(PACKER, tool / "pack-evidence.sh")
        evidence = self.root / ".press" / "ci-selftest"
        evidence.mkdir(parents=True)
        (evidence / "a:b.txt").write_bytes(b"path-boundary evidence")
        (evidence.parent / "ci-selftest.log").write_bytes(b"detector output")
        result = subprocess.run(
            ["bash", "-c", commands[0]], cwd=self.root, capture_output=True, text=True, timeout=10
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        start = lines.index("        path: |") + 1
        paths = []
        for line in lines[start:]:
            if not line.startswith("          "):
                break
            paths.append(self.root / line.strip())
        self.assertTrue(paths, "upload must include evidence")
        self.assertTrue(
            all(p.is_file() for p in paths), "upload must use portable files, not the raw tree"
        )
        self.assertTrue(all(all(c not in p.name for c in '":<>|*?\r\n') for p in paths))
        self.assertIn(evidence.parent / "ci-selftest.log", paths, "retain the separate command log")
        archive = evidence.parent / "ci-selftest.tar.gz"
        self.assertIn(archive, paths, "upload the packaged browser evidence")
        self.assertEqual(
            archive.read_bytes()[:2], b"\x1f\x8b", "workflow must package actual gzip bytes"
        )


if __name__ == "__main__":
    unittest.main()
