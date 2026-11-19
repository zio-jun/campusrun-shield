package dev.campus.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class Geo {
    static final double RADIUS = 6_371_008.8;
    static final double PER_DEGREE = Math.PI * RADIUS / 180;

    static final class Point {
        final double latitude;
        final double longitude;

        Point(double latitude, double longitude) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("经度或纬度超出范围");
            }
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static double distance(Point from, Point to) {
        double dLat = Math.toRadians(to.latitude - from.latitude);
        double dLon = Math.toRadians(Math.IEEEremainder(to.longitude - from.longitude, 360));
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat + Math.cos(Math.toRadians(from.latitude))
                * Math.cos(Math.toRadians(to.latitude)) * sinLon * sinLon;
        return RADIUS * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
    }

    static double bearing(Point from, Point to) {
        double fromLat = Math.toRadians(from.latitude);
        double toLat = Math.toRadians(to.latitude);
        double dLon = Math.toRadians(to.longitude - from.longitude);
        double x = Math.sin(dLon) * Math.cos(toLat);
        double y = Math.cos(fromLat) * Math.sin(toLat)
                - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
    }

    private static Point advance(Point from, double meters, double bearing) {
        double lat = Math.toRadians(from.latitude);
        double lon = Math.toRadians(from.longitude);
        double direction = Math.toRadians(bearing);
        double arc = meters / RADIUS;
        double sinNextLat = Math.sin(lat) * Math.cos(arc)
                + Math.cos(lat) * Math.sin(arc) * Math.cos(direction);
        double nextLat = Math.asin(Math.max(-1, Math.min(1, sinNextLat)));
        double nextLon = lon + Math.atan2(Math.sin(direction) * Math.sin(arc) * Math.cos(lat),
                Math.cos(arc) - Math.sin(lat) * Math.sin(nextLat));
        return new Point(Math.toDegrees(nextLat), Math.IEEEremainder(Math.toDegrees(nextLon), 360));
    }

    static final class Sample {
        final Point point;
        final float bearing;

        Sample(Point point, double bearing) {
            this.point = point;
            this.bearing = (float) bearing;
        }
    }

    static final class Route {
        final List<Point> points;
        final double meters;
        private final double[] positions;
        private final double[] bearings;

        Route(List<Point> raw) {
            ArrayList<Point> clean = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                Point point = raw.get(i);
                if (clean.isEmpty() || distance(clean.get(clean.size() - 1), point) >= 0.01) {
                    clean.add(point);
                } else if (i == raw.size() - 1 && clean.size() > 1) {
                    clean.set(clean.size() - 1, point);
                }
            }
            if (clean.size() < 2) {
                throw new IllegalArgumentException("请设置有距离的路线");
            }
            points = Collections.unmodifiableList(clean);
            positions = new double[clean.size()];
            bearings = new double[clean.size() - 1];
            for (int i = 1; i < clean.size(); i++) {
                positions[i] = positions[i - 1] + distance(clean.get(i - 1), clean.get(i));
                bearings[i - 1] = bearing(clean.get(i - 1), clean.get(i));
            }
            meters = positions[positions.length - 1];
        }

        Sample at(double distance) {
            if (distance <= 0) {
                return new Sample(points.get(0), bearings[0]);
            }
            if (distance >= meters) {
                return new Sample(points.get(points.size() - 1), bearings[bearings.length - 1]);
            }
            int index = Arrays.binarySearch(positions, distance);
            if (index >= 0) {
                return new Sample(points.get(index), bearings[index]);
            }
            int leg = -index - 2;
            Point point = advance(points.get(leg), distance - positions[leg], bearings[leg]);
            return new Sample(point, bearing(point, points.get(leg + 1)));
        }

        double[] coordinates() {
            double[] result = new double[points.size() * 2];
            for (int i = 0; i < points.size(); i++) {
                result[2 * i] = points.get(i).latitude;
                result[2 * i + 1] = points.get(i).longitude;
            }
            return result;
        }

        static Route fromCoordinates(double[] xy) {
            if (xy == null || xy.length < 4 || xy.length % 2 != 0) {
                throw new IllegalArgumentException("路线数据不完整");
            }
            ArrayList<Point> points = new ArrayList<>(xy.length / 2);
            for (int i = 0; i < xy.length; i += 2) {
                points.add(new Point(xy[i], xy[i + 1]));
            }
            return new Route(points);
        }
    }

    private Geo() {}
}
