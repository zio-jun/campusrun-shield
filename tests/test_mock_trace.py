import unittest

from sentinel.mock_trace import Point, analyze


class TestMockTrace(unittest.TestCase):
    def _fake_trace(self):
        pts = []
        lat = 30.51
        t0 = 1700000000000
        for i in range(120):
            pts.append(Point(t=t0 + i * 1000, lat=lat + i * 3.2 / 111320.0, lng=114.41))
        return pts

    def _real_trace(self):
        pts = []
        lat, lng = 30.51, 114.41
        t0 = 1700000000000
        jitter = [0.9, 1.6, 3.4, 2.7, 4.1, 2.2, 3.0, 1.1]
        for i in range(120):
            v = jitter[i % len(jitter)]
            lat += v / 111320.0
            lng += (0.2 if i % 7 else -0.1) / 111320.0
            pts.append(Point(t=t0 + i * 1000 + (i % 3) * 137, lat=lat, lng=lng))
        return pts

    def test_fake_higher_risk(self):
        fake = analyze(self._fake_trace())
        real = analyze(self._real_trace())
        self.assertGreater(fake.score, real.score)
        self.assertGreaterEqual(fake.score, 40)
        self.assertTrue(any("钳制" in f or "恒定" in f or "等间隔" in f or "熵" in f
                            for f in fake.flags))

    def test_short_trace(self):
        rep = analyze([Point(1, 1.0, 1.0), Point(2, 1.0, 1.0)])
        self.assertEqual(rep.score, 100)
        self.assertTrue(rep.flags)

    def test_speed_fields(self):
        rep = analyze(self._real_trace())
        self.assertGreater(rep.speed_mean, 0)
        self.assertGreater(rep.distance_m, 0)
        self.assertAlmostEqual(rep.speed_cv, rep.speed_std / rep.speed_mean, places=2)


if __name__ == "__main__":
    unittest.main()
