package dev.campus.route;

import android.content.res.AssetManager;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

final class CampusMap {
    /** 默认投影原点（华科主校区）。内置地图或空白模式未选点前使用。 */
    static final double DEFAULT_LAT = 30.518;
    static final double DEFAULT_LON = 114.413;
    private static double originLat = DEFAULT_LAT;
    private static double originLon = DEFAULT_LON;
    private static double lonMeters = Geo.PER_DEGREE * Math.cos(Math.toRadians(originLat));
    private static final double MAX_SNAP = 60;
    private static final int MAX_SHAPES = 20_000;
    private static final int MAX_LABELS = 400;
    private static final int MAX_VERTICES = 200_000;
    private static final int MAX_EDGES = 300_000;

    static final class Shape {
        final int kind;
        final Path path;
        final RectF bounds = new RectF();

        Shape(JSONArray geometry) throws JSONException {
            this(geometry.getInt(0), legacyPath(geometry));
        }

        Shape(int kind, Path path) {
            this.kind = kind;
            this.path = path;
            path.computeBounds(bounds, true);
        }

        private static Path legacyPath(JSONArray geometry) throws JSONException {
            Path path = new Path();
            path.moveTo(geometry.getInt(1) / 10f, geometry.getInt(2) / 10f);
            for (int i = 3; i < geometry.length(); i += 2) {
                path.lineTo(geometry.getInt(i) / 10f, geometry.getInt(i + 1) / 10f);
            }
            if (geometry.getInt(0) < 5 || geometry.getInt(0) == 9) {
                path.close();
            }
            return path;
        }
    }

    static final class Label {
        final float x;
        final float y;
        final String name;
        final int priority;

        Label(JSONArray data) throws JSONException {
            this(data.getInt(0) / 10f, data.getInt(1) / 10f, data.getString(2), data.getInt(3));
        }

        Label(float x, float y, String name, int priority) {
            this.x = x;
            this.y = y;
            this.name = name;
            this.priority = priority;
        }
    }

    private static final class Edge {
        final int a;
        final int b;
        final double length;

        Edge(int a, int b, double length) {
            this.a = a;
            this.b = b;
            this.length = length;
        }

        int other(int vertex) {
            return vertex == a ? b : a;
        }
    }

    private static final class Snap {
        final Edge edge;
        final double fraction;
        final double x;
        final double y;

        Snap(Edge edge, double fraction, double x, double y) {
            this.edge = edge;
            this.fraction = fraction;
            this.x = x;
            this.y = y;
        }
    }

    private static final class Visit implements Comparable<Visit> {
        final int vertex;
        final double distance;

        Visit(int vertex, double distance) {
            this.vertex = vertex;
            this.distance = distance;
        }

        @Override public int compareTo(Visit other) {
            return Double.compare(distance, other.distance);
        }
    }

    /** GeoJSON 中间表示：kind 0=面、5=线、-1=点；rings 为扁平 [lon,lat,...] 坐标串。 */
    private static final class Feature {
        final String name;
        final int kind;
        final List<double[]> rings = new ArrayList<>();

        Feature(String name, int kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    final RectF bounds;
    final List<Shape> shapes;
    final List<Label> labels;
    final double mapOriginLat;
    final double mapOriginLon;
    private final double[] x;
    private final double[] y;
    private final List<Edge> edges = new ArrayList<>();
    private final List<List<Edge>> byVertex = new ArrayList<>();

    private CampusMap(double originLat, double originLon, RectF bounds,
                      List<Shape> shapes, List<Label> labels,
                      double[] x, double[] y, int[] edgePairs) {
        this.mapOriginLat = originLat;
        this.mapOriginLon = originLon;
        this.bounds = bounds;
        this.shapes = shapes;
        this.labels = labels;
        this.x = x;
        this.y = y;
        for (int i = 0; i < x.length; i++) {
            byVertex.add(new ArrayList<>());
        }
        for (int i = 0; i + 1 < edgePairs.length; i += 2) {
            int a = edgePairs[i];
            int b = edgePairs[i + 1];
            if (a < 0 || b < 0 || a >= x.length || b >= x.length || a == b) {
                continue;
            }
            Edge edge = new Edge(a, b, Math.hypot(x[b] - x[a], y[b] - y[a]));
            edges.add(edge);
            byVertex.get(a).add(edge);
            byVertex.get(b).add(edge);
        }
    }

    static CampusMap load(AssetManager assets) throws IOException, JSONException {
        try (InputStream stream = assets.open("campus-map.json")) {
            return parse(readFully(stream));
        }
    }

    /**
     * 解析地图文本：兼容 campus-map.json 格式（prepare_map.py 产物，可带 origin）
     * 与 GeoJSON（FeatureCollection / Feature / 几何对象，WGS84 经纬度）。
     */
    static CampusMap parse(String content) throws IOException, JSONException {
        JSONObject root = new JSONObject(content);
        CampusMap map;
        if (root.has("geometry") && root.has("bounds")) {
            map = fromLegacy(root);
        } else {
            map = fromGeoJson(root);
        }
        if (map.shapes.isEmpty() && map.x.length == 0) {
            throw new IllegalArgumentException("地图文件没有可用的图形或路网数据");
        }
        return map;
    }

    private static String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- 投影（全局原点，随地图/空白模式动态调整） ----

    static void setOrigin(double lat, double lon) {
        originLat = Math.max(-90, Math.min(90, lat));
        originLon = Math.IEEEremainder(lon, 360);
        lonMeters = Geo.PER_DEGREE * Math.cos(Math.toRadians(originLat));
    }

    static double x(Geo.Point point) {
        return Math.IEEEremainder(point.longitude - originLon, 360) * lonMeters;
    }

    static double y(Geo.Point point) {
        return (originLat - point.latitude) * Geo.PER_DEGREE;
    }

    static Geo.Point point(double x, double y) {
        double lat = Math.max(-90, Math.min(90, originLat - y / Geo.PER_DEGREE));
        return new Geo.Point(lat, Math.IEEEremainder(originLon + x / lonMeters, 360));
    }

    // ---- campus-map.json 格式 ----

    private static CampusMap fromLegacy(JSONObject data) throws JSONException {
        JSONArray origin = data.optJSONArray("origin");
        double lat = origin != null && origin.length() >= 2 ? origin.getDouble(0) : DEFAULT_LAT;
        double lon = origin != null && origin.length() >= 2 ? origin.getDouble(1) : DEFAULT_LON;

        JSONArray rectangle = data.getJSONArray("bounds");
        RectF bounds = new RectF(rectangle.getInt(0) / 10f, rectangle.getInt(1) / 10f,
                rectangle.getInt(2) / 10f, rectangle.getInt(3) / 10f);

        JSONArray allShapes = data.getJSONArray("geometry");
        List<Shape> shapes = new ArrayList<>(allShapes.length());
        for (int i = 0; i < allShapes.length(); i++) {
            shapes.add(new Shape(allShapes.getJSONArray(i)));
        }

        JSONArray allLabels = data.getJSONArray("labels");
        List<Label> labels = new ArrayList<>(allLabels.length());
        for (int i = 0; i < allLabels.length(); i++) {
            labels.add(new Label(allLabels.getJSONArray(i)));
        }
        labels.sort((first, second) -> Integer.compare(first.priority, second.priority));

        JSONArray vertices = data.optJSONArray("vertices");
        JSONArray pairs = data.optJSONArray("edges");
        int count = vertices == null ? 0 : vertices.length();
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            JSONArray point = vertices.getJSONArray(i);
            x[i] = point.getInt(0) / 10.0;
            y[i] = point.getInt(1) / 10.0;
        }
        int[] edgePairs = new int[pairs == null ? 0 : pairs.length()];
        for (int i = 0; i < edgePairs.length; i++) {
            edgePairs[i] = pairs.getInt(i);
        }
        return new CampusMap(lat, lon, bounds, shapes, labels, x, y, edgePairs);
    }

    // ---- GeoJSON ----

    private static CampusMap fromGeoJson(JSONObject root) throws JSONException {
        List<Feature> features = new ArrayList<>();
        collect(root, features);
        if (features.isEmpty()) {
            throw new IllegalArgumentException("GeoJSON 没有可用的要素");
        }

        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (Feature feature : features) {
            for (double[] ring : feature.rings) {
                for (int i = 0; i + 1 < ring.length; i += 2) {
                    minLat = Math.min(minLat, ring[i + 1]);
                    maxLat = Math.max(maxLat, ring[i + 1]);
                    minLon = Math.min(minLon, ring[i]);
                    maxLon = Math.max(maxLon, ring[i]);
                }
            }
        }
        if (!Double.isFinite(minLat)) {
            throw new IllegalArgumentException("GeoJSON 坐标无效");
        }
        double lat0 = (minLat + maxLat) / 2;
        double lon0 = (minLon + maxLon) / 2;
        double meters = Geo.PER_DEGREE * Math.cos(Math.toRadians(lat0));

        List<Shape> shapes = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        HashSet<String> usedNames = new HashSet<>();
        for (Feature feature : features) {
            if (shapes.size() >= MAX_SHAPES) {
                break;
            }
            if (feature.kind < 0) {
                if (feature.name != null && !feature.rings.isEmpty()
                        && labels.size() < MAX_LABELS && usedNames.add(feature.name)) {
                    double[] p = feature.rings.get(0);
                    labels.add(new Label((float) localX(p[0], lon0, meters),
                            (float) localY(p[1], lat0), feature.name, 0));
                }
                continue;
            }
            for (double[] ring : feature.rings) {
                if (ring.length < 4) {
                    continue;
                }
                Path path = new Path();
                path.moveTo((float) localX(ring[0], lon0, meters),
                        (float) localY(ring[1], lat0));
                for (int i = 2; i + 1 < ring.length; i += 2) {
                    path.lineTo((float) localX(ring[i], lon0, meters),
                            (float) localY(ring[i + 1], lat0));
                }
                if (feature.kind == 0) {
                    path.close();
                }
                shapes.add(new Shape(feature.kind, path));
                if (shapes.size() >= MAX_SHAPES) {
                    break;
                }
            }
            if (feature.name != null && !feature.rings.isEmpty()
                    && labels.size() < MAX_LABELS && usedNames.add(feature.name)) {
                double[] ring = feature.rings.get(0);
                double minX = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;
                for (int i = 0; i + 1 < ring.length; i += 2) {
                    minX = Math.min(minX, localX(ring[i], lon0, meters));
                    maxX = Math.max(maxX, localX(ring[i], lon0, meters));
                    minY = Math.min(minY, localY(ring[i + 1], lat0));
                    maxY = Math.max(maxY, localY(ring[i + 1], lat0));
                }
                labels.add(new Label((float) ((minX + maxX) / 2), (float) ((minY + maxY) / 2),
                        feature.name, 0));
            }
        }
        labels.sort((first, second) -> Integer.compare(first.priority, second.priority));

        // 由 LineString / MultiLineString 构建步行路网（顶点按 0.1m 去重）
        HashMap<Long, Integer> ids = new HashMap<>();
        ArrayList<double[]> xy = new ArrayList<>();
        List<int[]> edgeList = new ArrayList<>();
        for (Feature feature : features) {
            if (feature.kind != 5) {
                continue;
            }
            for (double[] ring : feature.rings) {
                int previous = -1;
                for (int i = 0; i + 1 < ring.length; i += 2) {
                    if (xy.size() >= MAX_VERTICES) {
                        break;
                    }
                    long key = vertexKey(ring[i], ring[i + 1], lon0, lat0, meters);
                    Integer id = ids.get(key);
                    if (id == null) {
                        if (xy.size() >= MAX_VERTICES) {
                            continue;
                        }
                        id = xy.size();
                        ids.put(key, id);
                        xy.add(new double[]{localX(ring[i], lon0, meters),
                                localY(ring[i + 1], lat0)});
                    }
                    if (previous >= 0 && previous != id && edgeList.size() < MAX_EDGES) {
                        edgeList.add(new int[]{previous, id});
                    }
                    previous = id;
                }
            }
        }
        double[] x = new double[xy.size()];
        double[] y = new double[xy.size()];
        for (int i = 0; i < xy.size(); i++) {
            x[i] = xy.get(i)[0];
            y[i] = xy.get(i)[1];
        }
        int[] edgePairs = new int[edgeList.size() * 2];
        for (int i = 0; i < edgeList.size(); i++) {
            edgePairs[2 * i] = edgeList.get(i)[0];
            edgePairs[2 * i + 1] = edgeList.get(i)[1];
        }

        RectF bounds = new RectF((float) localX(minLon, lon0, meters),
                (float) localY(maxLat, lat0),
                (float) localX(maxLon, lon0, meters),
                (float) localY(minLat, lat0));
        return new CampusMap(lat0, lon0, bounds, shapes, labels, x, y, edgePairs);
    }

    private static double localX(double lon, double lon0, double meters) {
        return (lon - lon0) * meters;
    }

    private static double localY(double lat, double lat0) {
        return (lat0 - lat) * Geo.PER_DEGREE;
    }

    private static long vertexKey(double lon, double lat, double lon0, double lat0, double meters) {
        long ix = Math.round(localX(lon, lon0, meters) * 10);
        long iy = Math.round(localY(lat, lat0) * 10);
        return (ix << 32) ^ (iy & 0xFFFFFFFFL);
    }

    private static void collect(Object node, List<Feature> features) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String type = object.optString("type");
            if ("FeatureCollection".equals(type)) {
                JSONArray list = object.optJSONArray("features");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        collect(list.opt(i), features);
                    }
                }
            } else if ("Feature".equals(type)) {
                JSONObject properties = object.optJSONObject("properties");
                String name = trimName(properties == null ? null : properties.optString("name"));
                JSONObject geometry = object.optJSONObject("geometry");
                if (geometry == null) {
                    collectGeometry(name, "GeometryCollection",
                            object.optJSONArray("geometry"), features);
                } else {
                    collectGeometry(name, geometry.optString("type"),
                            geometry.optJSONArray("coordinates"), features);
                }
            } else if ("GeometryCollection".equals(type)) {
                JSONArray list = object.optJSONArray("geometries");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        collect(list.opt(i), features);
                    }
                }
            } else if (object.has("coordinates")) {
                collectGeometry(null, type, object.optJSONArray("coordinates"), features);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collect(array.opt(i), features);
            }
        }
    }

    private static void collectGeometry(String name, String type, JSONArray coordinates,
                                        List<Feature> features) throws JSONException {
        if (coordinates == null) {
            return;
        }
        switch (type == null ? "" : type) {
            case "Point": {
                double[] point = flat(coordinates, 2);
                if (point != null) {
                    Feature feature = new Feature(name, -1);
                    feature.rings.add(point);
                    features.add(feature);
                }
                break;
            }
            case "MultiPoint": {
                Feature feature = new Feature(name, -1);
                for (int i = 0; i < coordinates.length(); i++) {
                    double[] point = flat(coordinates.optJSONArray(i), 2);
                    if (point != null) {
                        feature.rings.add(point);
                    }
                }
                if (!feature.rings.isEmpty()) {
                    features.add(feature);
                }
                break;
            }
            case "LineString":
                addRings(name, 5, new JSONArray().put(coordinates), features);
                break;
            case "MultiLineString": {
                Feature feature = new Feature(name, 5);
                for (int i = 0; i < coordinates.length(); i++) {
                    double[] ring = flat(coordinates.optJSONArray(i), 4);
                    if (ring != null) {
                        feature.rings.add(ring);
                    }
                }
                if (!feature.rings.isEmpty()) {
                    features.add(feature);
                }
                break;
            }
            case "Polygon":
                addRings(name, 0, coordinates, features);
                break;
            case "MultiPolygon": {
                Feature feature = new Feature(name, 0);
                for (int i = 0; i < coordinates.length(); i++) {
                    JSONArray polygon = coordinates.optJSONArray(i);
                    if (polygon == null) {
                        continue;
                    }
                    for (int j = 0; j < polygon.length(); j++) {
                        double[] ring = flat(polygon.optJSONArray(j), 4);
                        if (ring != null) {
                            feature.rings.add(ring);
                        }
                    }
                }
                if (!feature.rings.isEmpty()) {
                    features.add(feature);
                }
                break;
            }
            default:
                break;
        }
    }

    private static void addRings(String name, int kind, JSONArray rings,
                                 List<Feature> features) throws JSONException {
        Feature feature = new Feature(name, kind);
        for (int i = 0; i < rings.length(); i++) {
            double[] ring = flat(rings.optJSONArray(i), 4);
            if (ring != null) {
                feature.rings.add(ring);
            }
        }
        if (!feature.rings.isEmpty()) {
            features.add(feature);
        }
    }

    private static double[] flat(JSONArray positions, int min) throws JSONException {
        if (positions == null || positions.length() < min || positions.length() % 2 != 0) {
            return null;
        }
        double[] result = new double[positions.length()];
        for (int i = 0; i < positions.length(); i++) {
            double value = positions.getDouble(i);
            if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) {
                return null;
            }
            result[i] = value;
        }
        return result;
    }

    private static String trimName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- 沿路网规划（与原版一致） ----

    List<Geo.Point> walkingLeg(Geo.Point from, Geo.Point to) {
        Snap start = nearest(from);
        Snap end = nearest(to);
        if (start == null || end == null) {
            return Collections.emptyList();
        }
        ArrayList<Geo.Point> result = new ArrayList<>();
        result.add(from);
        result.add(point(start.x, start.y));
        if (start.edge != end.edge) {
            List<Integer> path = path(start, end);
            if (path.isEmpty()) {
                return Collections.emptyList();
            }
            for (int vertex : path) {
                result.add(point(x[vertex], y[vertex]));
            }
        }
        result.add(point(end.x, end.y));
        result.add(to);
        return result;
    }

    private Snap nearest(Geo.Point point) {
        double px = x(point);
        double py = y(point);
        double bestSquared = MAX_SNAP * MAX_SNAP;
        Snap best = null;
        for (Edge edge : edges) {
            double ax = x[edge.a];
            double ay = y[edge.a];
            double dx = x[edge.b] - ax;
            double dy = y[edge.b] - ay;
            double along = ((px - ax) * dx + (py - ay) * dy) / (edge.length * edge.length);
            double fraction = Math.min(1, Math.max(0, along));
            double sx = ax + fraction * dx;
            double sy = ay + fraction * dy;
            double squared = (px - sx) * (px - sx) + (py - sy) * (py - sy);
            if (squared < bestSquared) {
                bestSquared = squared;
                best = new Snap(edge, fraction, sx, sy);
            }
        }
        return best;
    }

    private List<Integer> path(Snap start, Snap end) {
        double[] distances = new double[x.length];
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
        int[] parents = new int[x.length];
        Arrays.fill(parents, -1);
        PriorityQueue<Visit> queue = new PriorityQueue<>();
        distances[start.edge.a] = start.edge.length * start.fraction;
        distances[start.edge.b] = start.edge.length * (1 - start.fraction);
        queue.add(new Visit(start.edge.a, distances[start.edge.a]));
        queue.add(new Visit(start.edge.b, distances[start.edge.b]));

        double best = Double.POSITIVE_INFINITY;
        int finish = -1;
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            int vertex = visit.vertex;
            if (visit.distance >= best) {
                break;
            }
            if (visit.distance > distances[vertex]) {
                continue;
            }
            if (vertex == end.edge.a || vertex == end.edge.b) {
                double rest = vertex == end.edge.a ? end.fraction : 1 - end.fraction;
                double score = visit.distance + end.edge.length * rest;
                if (score < best) {
                    best = score;
                    finish = vertex;
                }
            }
            for (Edge edge : byVertex.get(vertex)) {
                int neighbor = edge.other(vertex);
                double next = visit.distance + edge.length;
                if (next < distances[neighbor]) {
                    distances[neighbor] = next;
                    parents[neighbor] = vertex;
                    queue.add(new Visit(neighbor, next));
                }
            }
        }

        ArrayList<Integer> path = new ArrayList<>();
        for (int vertex = finish; vertex != -1; vertex = parents[vertex]) {
            path.add(vertex);
        }
        Collections.reverse(path);
        return path;
    }
}
