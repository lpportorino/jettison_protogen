"""Image identity remains stable across locales and callers; missing pins fail."""

import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ImageTagTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "bin").mkdir()
        (self.root / "pins").mkdir()
        shutil.copy2(
            Path(__file__).resolve().parents[1] / "bin/image-tag", self.root / "bin/image-tag"
        )
        (self.root / "Dockerfile").write_text("FROM example\n")
        for name in ("z.lock", "a.lock", "B.lock"):
            (self.root / "pins" / name).write_text(name + "\n")

    def run_tag(self, locale="C"):
        return subprocess.run(
            ["env", f"LC_ALL={locale}", str(self.root / "bin/image-tag")],
            cwd="/",
            capture_output=True,
            text=True,
            check=False,
        )

    def test_named_digests_in_c_order_independent_of_locale_and_cwd(self):
        records = ""
        for name in ("Dockerfile", "pins/B.lock", "pins/a.lock", "pins/z.lock"):
            records += (
                hashlib.sha256((self.root / name).read_bytes()).hexdigest() + "  " + name + "\n"
            )
        expected = "protogen_press:" + hashlib.sha256(records.encode()).hexdigest()[:12] + "\n"
        for locale in ("C", "C.utf8"):
            with self.subTest(locale=locale):
                result = self.run_tag(locale)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, expected)

    def test_pin_change_changes_identity(self):
        before = self.run_tag().stdout
        (self.root / "pins/a.lock").write_text("different pin")
        self.assertNotEqual(before, self.run_tag().stdout)

    def test_missing_dockerfile_fails(self):
        (self.root / "Dockerfile").unlink()
        self.assertNotEqual(self.run_tag().returncode, 0)

    def test_empty_pins_fail(self):
        for pin in (self.root / "pins").iterdir():
            pin.unlink()
        self.assertNotEqual(self.run_tag().returncode, 0)

    def test_symlink_pin_fails(self):
        (self.root / "pins/a.lock").unlink()
        (self.root / "pins/a.lock").symlink_to(self.root / "Dockerfile")
        self.assertNotEqual(self.run_tag().returncode, 0)
