"""Build the offline map asset from an OpenStreetMap API 0.6 XML extract."""

import argparse
import json
import math
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

LAT, LON = 30.518, 114.413
RADIUS = 6_371_008.8
DEGREES = math.pi / 180
SOURCE = "https://api.openstreetmap.org/api/0.6/map?bbox=114.389,30.501,114.447,30.537"

STREETS = {"residential", "unclassified", "service", "living_street", "track"}
MAIN_ROADS = {"primary", "secondary", "tertiary"}
PATHS = {"footway", "pedestrian", "path", "steps", "cycleway"}
LABELS = {
    "华中科技大学": (0, "华中科技大学"),
    "中心操场": (0, "中心操场"),
    "西操场": (0, "西操场"),
    "东操场": (0, "东操场"),
    "光谷体育馆": (0, "光谷体育馆"),
    "青年园": (1, "青年园"),
    "图书馆": (0, "图书馆"),
    "南大门": (0, "南大门"),
    "华中科技大学西小门(紫菘门)": (1, "紫菘门"),
    "西北门": (1, "西北门"),
    "喻家湖路华工东北门": (1, "东北门"),
    "中国地质大学（武汉）": (1, "中国地质大学"),
    "东湖": (0, "东湖"),
    "喻园小区": (1, "喻园小区"),
    "北区体育馆": (1, "北区体育馆"),
    "韵苑体育馆": (1, "韵苑体育馆"),
    "东九教学楼": (1, "东九教学楼"),
    "光谷广场": (1, "光谷广场"),
}


def tags(element):
    return {tag.attrib["k"]: tag.attrib["v"] for tag in element.findall("tag")}


def project(lat, lon):
    x = RADIUS * DEGREES * (float(lon) - LON) * math.cos(LAT * DEGREES)
    y = RADIUS * DEGREES * (LAT - float(lat))
    return round(x * 10), round(y * 10)


def layer(t, closed):
    highway = t.get("highway")
    if t.get("leisure") == "track":
        return 8
    if highway in PATHS:
        return 0 if closed and t.get("area") == "yes" else 5
    if highway in STREETS:
        return 6
    if highway in MAIN_ROADS or highway in {"trunk", "primary_link", "trunk_link"}:
        return 7
    if not closed:
        return None
    if t.get("natural") in {"water", "wetland"} or t.get("waterway") == "riverbank":
        return 2
    if t.get("leisure") in {"pitch", "sports_centre", "stadium"} and not t.get("building"):
        return 3
    if t.get("building") not in {None, "no"}:
        return 4
    if t.get("natural") in {"wood", "scrub"} or t.get("landuse") == "forest":
        return 1
    if t.get("landuse") in {"grass", "meadow", "recreation_ground"} or t.get("leisure") in {"park", "garden"}:
        return 0
    if t.get("amenity") == "university":
        return 9
    return None


def walkable(t):
    highway = t.get("highway")
    if t.get("area") == "yes" or t.get("foot") in {"no", "private"}:
        return False
    if t.get("access") in {"no", "private"} and t.get("foot") not in {"yes", "designated", "permissive"}:
        return False
    if t.get("foot") in {"yes", "designated"} and highway:
        return True
    return highway in (STREETS | MAIN_ROADS | PATHS) or t.get("leisure") == "track"


def label(element, coords, labels):
    t = tags(element)
    name = t.get("name:zh") or t.get("name")
    if name not in LABELS or not coords:
        return
    priority, short_name = LABELS[name]
    if short_name in labels:
        return
    x0, y0, x1, y1 = box(coords)
    labels[short_name] = [(x0 + x1) // 2, (y0 + y1) // 2, short_name, priority]


def box(coords):
    xs, ys = zip(*coords)
    return min(xs), min(ys), max(xs), max(ys)


def build(source):
    root = ET.parse(source).getroot()
    osm_bounds = root.find("bounds").attrib
    bounds = box([
        project(osm_bounds["minlat"], osm_bounds["minlon"]),
        project(osm_bounds["maxlat"], osm_bounds["maxlon"]),
    ])
    nodes, labels, layers, ways = {}, {}, defaultdict(list), {}
    graph_points, graph_ids, edges = [], {}, set()

    def in_bounds(x, y, margin=0):
        return bounds[0] - margin <= x <= bounds[2] + margin and bounds[1] - margin <= y <= bounds[3] + margin

    def vertex(ref):
        if ref not in graph_ids:
            graph_ids[ref] = len(graph_points)
            graph_points.append(nodes[ref])
        return graph_ids[ref]

    for node in root.findall("node"):
        coord = project(node.attrib["lat"], node.attrib["lon"])
        nodes[node.attrib["id"]] = coord
        if in_bounds(*coord):
            label(node, [coord], labels)

    for way in root.findall("way"):
        refs = [nd.attrib["ref"] for nd in way.findall("nd") if nd.attrib["ref"] in nodes]
        if len(refs) < 2:
            continue
        xy = [nodes[ref] for ref in refs]
        ways[way.attrib["id"]] = xy
        label(way, xy, labels)
        t = tags(way)
        kind = layer(t, refs[0] == refs[-1])
        if kind is not None:
            layers[kind].append([kind, *[number for coord in xy for number in coord]])
        if walkable(t):
            for a, b in zip(refs, refs[1:]):
                if nodes[a] != nodes[b] and in_bounds(*nodes[a], 100) and in_bounds(*nodes[b], 100):
                    edges.add(tuple(sorted((vertex(a), vertex(b)))))

    for relation in root.findall("relation"):
        t = tags(relation)
        if t.get("type") != "multipolygon":
            continue
        kind = layer(t, True)
        if kind is None or kind in (5, 6, 7, 8):
            continue
        rings = [ways[m.attrib["ref"]] for m in relation.findall("member")
                 if m.attrib.get("type") == "way" and m.attrib["ref"] in ways]
        for ring in rings:
            if ring[0] == ring[-1]:
                layers[kind].append([kind, *[number for coord in ring for number in coord]])
        label(relation, [coord for ring in rings for coord in ring], labels)

    draw_order = (9, 0, 1, 2, 3, 4, 5, 6, 7, 8)
    return {
        "origin": [LAT, LON],
        "source": SOURCE,
        "bounds": bounds,
        "geometry": [line for kind in draw_order for line in layers[kind]],
        "labels": list(labels.values()),
        "vertices": graph_points,
        "edges": [index for edge in sorted(edges) for index in edge],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    result = build(args.source)
    args.destination.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"{len(result['geometry'])} map shapes, {len(result['vertices'])} route nodes, {len(result['edges']) // 2} edges")


if __name__ == "__main__":
    main()
