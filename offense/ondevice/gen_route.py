#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
campusrun_route.json 路线生成器
用法示例:
  # 圆形路线(半径200m, 速度2.5m/s, 循环)
  python gen_route.py --shape circle --center 30.5129,114.4096 --radius 200 -o campusrun_route.json

  # 跑道圆角矩形(直道100m + 两端半径18m半圆, 近似400m操场)
  python gen_route.py --shape stadium --center 30.5129,114.4096 --track-length 100 --radius 18 -o campusrun_route.json

  # 自定义点列(每行 lat,lng, 建议首尾闭合; 不闭合时 loop=true 会瞬移回起点)
  python gen_route.py --points my_route.txt --speed 2.8 --no-loop -o campusrun_route.json
"""
import argparse
import json
import math


def _offset(lat, lng, east_m, north_m):
    lat2 = lat + north_m / 111320.0
    lng2 = lng + east_m / (111320.0 * math.cos(math.radians(lat)))
    return [round(lat2, 7), round(lng2, 7)]


def circle(center, radius_m, segments=72):
    pts = [_offset(center[0], center[1],
                   math.sin(2 * math.pi * i / segments) * radius_m,
                   math.cos(2 * math.pi * i / segments) * radius_m)
           for i in range(segments)]
    pts.append(pts[0][:])
    return pts


def stadium(a, b, radius_m, spacing_m=5.0):
    base = a

    def to_local(c):
        return ((c[0] - base[0]) * 111320.0,
                (c[1] - base[1]) * 111320.0 * math.cos(math.radians(base[0])))

    def to_global(p):
        return [round(base[0] + p[0] / 111320.0, 7),
                round(base[1] + p[1] / (111320.0 * math.cos(math.radians(base[0]))), 7)]

    ax, ay = to_local(a)
    bx, by = to_local(b)
    L = math.hypot(bx - ax, by - ay) or 1e-6
    ux, uy = (bx - ax) / L, (by - ay) / L
    vx, vy = -uy, ux

    def P(x, y):
        return to_global((x, y))

    def arc(cx, cy, t0, t1):
        steps = max(8, int(math.pi * radius_m / spacing_m))
        return [P(cx + math.cos(t0 + (t1 - t0) * i / steps) * radius_m,
                  cy + math.sin(t0 + (t1 - t0) * i / steps) * radius_m)
                for i in range(steps + 1)]

    n = max(1, int(L / spacing_m))
    pts = [P(radius_m * vx + i / n * L * ux, radius_m * vy + i / n * L * uy)
           for i in range(n + 1)]                                    # 直道1
    pts += arc(L * ux, L * uy, math.pi / 2, -math.pi / 2)            # B 端外半圆
    pts += [P(L * ux - radius_m * vx - i / n * L * ux,
              L * uy - radius_m * vy - i / n * L * uy)
            for i in range(1, n + 1)]                                # 直道2
    pts += arc(0, 0, -math.pi / 2, -3 * math.pi / 2)                 # A 端外半圆
    pts.append(pts[0][:])
    return pts


def main():
    ap = argparse.ArgumentParser(description='生成 campusrun_route.json')
    ap.add_argument('-o', '--out', default='campusrun_route.json')
    ap.add_argument('--speed', type=float, default=2.5, help='配速 m/s (跑步 2~5)')
    ap.add_argument('--accuracy', type=float, default=3.0, help='定位精度 m')
    ap.add_argument('--loop', action='store_true', default=True, help='循环播放(默认)')
    ap.add_argument('--no-loop', action='store_false', dest='loop')
    ap.add_argument('--shape', choices=['circle', 'stadium'], default='circle')
    ap.add_argument('--center', default='30.5129,114.4096', help='基准中心 lat,lng')
    ap.add_argument('--radius', type=float, default=18.0, help='圆半径/弯道半径 m')
    ap.add_argument('--segments', type=int, default=72, help='圆的分段数')
    ap.add_argument('--track-length', type=float, default=100.0, help='跑道直道段长度 m')
    ap.add_argument('--spacing', type=float, default=5.0, help='采样间距 m')
    ap.add_argument('--points', help='点列文件(每行 lat,lng)')
    args = ap.parse_args()

    route = {'speed': args.speed, 'accuracy': args.accuracy, 'loop': args.loop}
    if args.points:
        pts = []
        with open(args.points, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                a, b = line.split(',')
                pts.append([float(a), float(b)])
        route['points'] = pts
    else:
        lat, lng = [float(x) for x in args.center.split(',')]
        if args.shape == 'circle':
            route['shape'] = 'circle'
            route['center'] = {'lat': lat, 'lng': lng}
            route['radius_m'] = args.radius
            route['segments'] = args.segments
        else:
            d_lng = args.track_length / (111320.0 * math.cos(math.radians(lat)))
            route['shape'] = 'stadium'
            route['a'] = {'lat': lat, 'lng': lng}
            route['b'] = {'lat': lat, 'lng': lng + d_lng}
            route['radius_m'] = args.radius
            route['spacing_m'] = args.spacing
    with open(args.out, 'w', encoding='utf-8') as f:
        json.dump(route, f, ensure_ascii=False, indent=2)
    print('written:', args.out)


if __name__ == '__main__':
    main()
