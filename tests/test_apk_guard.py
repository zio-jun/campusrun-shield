import os
import tempfile
import unittest
import zipfile

from sentinel import apk_guard


def make_apk(path, names):
    with zipfile.ZipFile(path, "w") as z:
        for n in names:
            z.writestr(n, b"x")


class TestApkGuard(unittest.TestCase):
    def test_detect_dongxiang(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "t.apk")
        make_apk(p, [
            "AndroidManifest.xml",
            "classes.dex",
            "lib/armeabi-v7a/libapk0000.so",
            "lib/armeabi-v7a/libstub000.so",
            "assets/csn1234.data",
        ])
        rep = apk_guard.inspect_apk(p)
        ids = [s.vendor_id for s in rep.shells]
        self.assertIn("dongxiang", ids)
        self.assertGreater(rep.score, 0)
        self.assertIn(rep.level, {"light", "medium", "strong"})

    def test_clean_apk(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "t.apk")
        make_apk(p, ["AndroidManifest.xml", "classes.dex", "res/a.png"])
        rep = apk_guard.inspect_apk(p)
        self.assertEqual(rep.level, "none")
        self.assertEqual(rep.shells, [])

    def test_suspicious_assets(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "t.apk")
        make_apk(p, ["classes.dex", "assets/csn8783f650.data", "assets/orig.apk"])
        rep = apk_guard.inspect_apk(p)
        self.assertTrue(any("csn" in a for a in rep.suspicious_assets))
        self.assertTrue(any("orig.apk" in a for a in rep.suspicious_assets))


if __name__ == "__main__":
    unittest.main()
