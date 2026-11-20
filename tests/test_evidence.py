import unittest

from sentinel import evidence


def _build_payload(distance=None, duration=None, n=60, speed=3.0, step_ms=1000):
    pts = []
    lat = 30.51
    t0 = 1700000000000
    for i in range(n):
        pts.append({"t": t0 + i * step_ms, "lat": lat + i * speed / 111320.0, "lng": 114.41})
    # 实际里程
    real = (n - 1) * speed
    return {
        "distance": distance if distance is not None else real,
        "duration": duration if duration is not None else (n - 1) * step_ms / 1000.0,
        "points": pts,
    }, real, (n - 1) * step_ms / 1000.0


class TestEvidence(unittest.TestCase):
    def test_consistent_payload_passes(self):
        payload, _, _ = _build_payload()
        rep = evidence.validate(payload)
        self.assertEqual(rep.verdict, "pass")

    def test_inflated_distance_rejected(self):
        payload, real, dur = _build_payload()
        payload["distance"] = real * 3
        rep = evidence.validate(payload)
        self.assertEqual(rep.failed, 1)
        self.assertEqual(rep.verdict, "warn")

    def test_missing_points(self):
        payload = {"distance": 3000, "duration": 1200, "points": []}
        rep = evidence.validate(payload)
        self.assertGreaterEqual(rep.failed, 1)

    def test_missing_duration(self):
        payload, _, _ = _build_payload()
        payload["duration"] = 0
        rep = evidence.validate(payload)
        codes = {f.code for f in rep.findings if not f.ok}
        self.assertIn("E004", codes)


if __name__ == "__main__":
    unittest.main()
