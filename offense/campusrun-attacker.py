#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CampusRun Attacker — 校园跑攻击测试床 CLI（仅授权环境）

覆盖三种代刷路径：
  A 方式一 · 本机虚拟环境改造：Gadget 注入重打包 APK（repack 子命令给指引）
    注：目标 App 带壳完整性校验时会触发 tamper 崩溃，需另行处理
  B 方式二 · 虚拟机 + 本地虚拟环境：redroid/模拟器 + frida-server（attach）
  C 方式三 · 应用级虚拟环境：双开/VMOS/VirtualApp 容器内运行 + 容器内 hook

B / C 不需要重打包 → 不触发壳的 dex 完整性校验，是"当前可直接跑通"的路径。

子命令：
  info      查看设备与目标 App 状态
  route     生成轨迹 JSON（圆形 / 跑道 / 点列，透传给 gen_route.py）
  push      推送 hook.js 与轨迹 JSON 到设备（--debug 打开日志）
  attach    frida 附加目标进程（需设备侧 frida-server 或容器内注入）
  repack    方式一重打包操作指引
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PKG = "net.crigh.hzkjsport"
HOOK = HERE / "ondevice" / "hook.js"
GEN = HERE / "ondevice" / "gen_route.py"
ROUTE_URLS = [
    "/sdcard/Download/campusrun_route.json",
    f"/sdcard/Android/data/{PKG}/files/campusrun_route.json",
]


def find_adb() -> str:
    exe = "adb.exe" if os.name == "nt" else "adb"
    cands = [
        shutil.which("adb"),
        shutil.which(exe),
        str(HERE.parent.parent / "work" / "hook-router" / "build" / "platform-tools" / exe),
        str(Path.home() / "AppData/Local/Android/Sdk/platform-tools" / exe),
        "/usr/lib/android-sdk/platform-tools/adb",
    ]
    for c in cands:
        if c and Path(c).exists():
            return str(c)
    sys.exit("未找到 adb：请安装 Android platform-tools 或设置 PATH")


def run(cmd, check=True):
    print("+", " ".join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], check=check)


def cmd_info(args):
    adb = find_adb()
    run([adb, "devices", "-l"], check=False)
    run([adb, "shell", "getprop", "ro.product.cpu.abilist"], check=False)
    run([adb, "shell", "pm", "path", PKG], check=False)


def cmd_route(args):
    argv = [sys.executable, str(GEN)] + args.rest
    run(argv)


def cmd_push(args):
    adb = find_adb()
    src = HOOK
    if args.debug:
        text = HOOK.read_text(encoding="utf-8").replace("DEBUG: false,", "DEBUG: true,")
        src = Path(tempfile.gettempdir()) / "campusrun_hook.js"
        src.write_text(text, encoding="utf-8")
        print(f"[debug] 已生成带日志版本: {src}")
    run([adb, "push", str(src), "/data/local/tmp/campusrun_hook.js"])
    run([adb, "shell", "mkdir", "-p", f"/sdcard/Android/data/{PKG}/files"], check=False)
    route = Path(args.route)
    if not route.exists():
        sys.exit(f"路线文件不存在: {route}（先用 route 子命令生成）")
    for url in ROUTE_URLS:
        run([adb, "push", str(route), url])


def cmd_attach(args):
    frida = shutil.which("frida")
    if not frida:
        print("未安装 frida CLI。安装：pip install frida-tools")
        print("设备侧需运行 frida-server（见 offense/vm/setup-frida.sh），或容器内已注入。")
        print("方式 C（容器）：请对容器进程注入 hook，或在容器内用 frida-server 附加目标进程。")
        sys.exit(1)
    cmd = [frida, "-U", "-f", PKG, "-l", str(HOOK), "--no-pause"]
    run(cmd, check=False)


def cmd_repack(args):
    print(
        """方式一（重打包）操作指引：
  1) apktool d -f -o apk-out target.apk
  2) 放入 lib/<abi>/libcampusrun.so 与 libcampusrun.config.so
  3) Application.attachBaseContext 首行插入 System.loadLibrary("campusrun")
  4) apktool b apk-out -o repacked.apk
  5) zipalign -p -f 4 repacked.apk aligned.apk
     apksigner sign --ks my.keystore --out final.apk aligned.apk
  6) adb uninstall <包名>; adb install final.apk

注意：目标 App 若带壳完整性校验，重打包会触发 "... is tamper" 崩溃；
可先按 docs/05 的实测案例定位校验调用点，或改用方式 B/C（不重打包）。"""
    )


def main():
    # route 子命令：参数整体透传给 gen_route.py（避免 argparse 吞掉 --shape 等参数）
    if len(sys.argv) >= 2 and sys.argv[1] == "route":
        run([sys.executable, str(GEN)] + sys.argv[2:])
        return

    ap = argparse.ArgumentParser(description="CampusRun Attacker 测试床 CLI（授权环境）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("info", help="设备与目标 App 状态").set_defaults(func=cmd_info)

    sub.add_parser("route", help="生成轨迹 JSON（参数透传 gen_route.py，如 --shape circle --radius 200）").set_defaults(func=cmd_route)

    p = sub.add_parser("push", help="推送 hook.js 与轨迹 JSON")
    p.add_argument("--route", default="campusrun_route.json")
    p.add_argument("--debug", action="store_true", help="推送 DEBUG=true 版本")
    p.set_defaults(func=cmd_push)

    p = sub.add_parser("attach", help="frida 附加目标进程")
    p.set_defaults(func=cmd_attach)

    sub.add_parser("repack", help="方式一重打包指引").set_defaults(func=cmd_repack)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
