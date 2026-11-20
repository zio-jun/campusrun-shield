import unittest

from sentinel.html_report import render_html

APK = {
    "path": "t.apk", "size": 1000, "entry_count": 5, "dex_count": 1,
    "v1_signed": True, "libs": {"armeabi-v7a": 2},
    "shells": [{"id": "dongxiang", "vendor": "顶象 DxShield", "evidence": ["com.security.shell"]}],
    "suspicious_assets": ["assets/csn1.data"], "score": 10, "level": "strong",
}
TRACE = {
    "n": 180, "duration_s": 179.0, "distance_m": 572.1, "speed_mean": 3.196,
    "speed_std": 0.049, "speed_cv": 0.015, "accel_spikes": 0, "sharp_turns": 0,
    "equal_interval_ratio": 1.0, "speed_entropy": 0.82, "clamp_ratio": 0.74,
    "score": 65, "flags": ["速度钳制特征"],
}
EV = {
    "verdict": "warn", "passed": 4, "failed": 2,
    "findings": [
        {"code": "E001", "ok": True, "detail": "轨迹点数：60"},
        {"code": "E002", "ok": False, "detail": "里程偏差 93%"},
    ],
}


class TestHtmlReport(unittest.TestCase):
    def test_full_render(self):
        html = render_html(APK, TRACE, EV)
        self.assertIn("Campus Run Sentinel", html)
        self.assertIn("顶象 DxShield", html)
        self.assertIn("65", html)
        self.assertIn("warn", html)
        self.assertIn("</html>", html)

    def test_partial_render(self):
        html = render_html(trace=TRACE)
        self.assertIn("轨迹可信度", html)
        self.assertNotIn("APK 加固评估", html)


if __name__ == "__main__":
    unittest.main()
