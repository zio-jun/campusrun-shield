#!/usr/bin/env bash
# =============================================================================
# setup-frida.sh —— 参数化部署 frida-server 到虚拟 Android 测试床
#
# 用途：仅用于授权安全评估与自建隔离实验环境（campusrun-shield · offense/vm）。
# 请勿用于任何生产环境、他人设备或真实校园运动数据。
#
# 功能：
#   1. 检测目标设备 ABI（adb shell getprop ro.product.cpu.abilist）
#   2. 下载匹配版本/ABI 的 frida-server（GitHub releases）
#   3. push 到设备并重命名（默认 frzs，规避壳内 frida 字符串检测）
#   4. 以 root 启动，监听非默认端口（默认 2742，规避 27042 端口扫描）
#   5. 建立 adb 反向端口转发（设备侧端口 → 宿主侧端口）
#
# 用法：
#   bash setup-frida.sh [选项]
#
# 选项：
#   --host HOST             adb 目标地址，默认 127.0.0.1（redroid/模拟器）
#   --port PORT             adb 端口，默认 5555
#   --serial SERIAL         直接指定 adb serial（如 emulator-5554），
#                           指定后忽略 --host/--port
#   --frida-version VER     frida-server 版本，默认 16.7.19
#                           （版本与本地 frida-tools 客户端需匹配）
#   --listen-port PORT      设备侧 frida 监听端口，默认 2742
#   --host-forward PORT     宿主侧转发端口，默认 2742
#   --alias NAME            push 后的二进制名，默认 frzs
#   --remote-dir DIR        设备上安装目录，默认 /data/local/tmp
#   --download-dir DIR      宿主下载目录，默认 ./downloads
#   --mirror URL            GitHub 下载镜像前缀（可选，如 https://ghproxy.com/）
#   --no-start              只部署不启动
#   --no-forward            不建端口转发
#   -h|--help               帮助
#
# 示例：
#   # redroid（WSL2）
#   bash setup-frida.sh --host 127.0.0.1 --port 5555
#
#   # Genymotion（x86_64 模拟器，注意 ABI 自动识别）
#   bash setup-frida.sh --serial 127.0.0.1:5555 --frida-version 16.6.6
#
#   # 完全自定义
#   bash setup-frida.sh --serial emulator-5554 --listen-port 9999 \
#        --alias srv_f --mirror https://ghproxy.com/
# =============================================================================
set -euo pipefail

# ---------------- 默认参数（全部可被命令行覆盖） ----------------
HOST="127.0.0.1"
PORT="5555"
SERIAL=""
FRIDA_VERSION="16.7.19"
LISTEN_PORT="2742"
HOST_FORWARD="2742"
ALIAS="frzs"
REMOTE_DIR="/data/local/tmp"
DOWNLOAD_DIR="./downloads"
MIRROR=""
START=1
FORWARD=1
ADB="adb"

# ---------------- 参数解析 ----------------
usage() {
  grep '^#' "$0" | sed 's/^# \{0,1\}//' | sed -n '1,60p'
}
while [ $# -gt 0 ]; do
  case "$1" in
    --host)          HOST="$2"; shift 2 ;;
    --port)          PORT="$2"; shift 2 ;;
    --serial)        SERIAL="$2"; shift 2 ;;
    --frida-version) FRIDA_VERSION="$2"; shift 2 ;;
    --listen-port)   LISTEN_PORT="$2"; shift 2 ;;
    --host-forward)  HOST_FORWARD="$2"; shift 2 ;;
    --alias)         ALIAS="$2"; shift 2 ;;
    --remote-dir)    REMOTE_DIR="$2"; shift 2 ;;
    --download-dir)  DOWNLOAD_DIR="$2"; shift 2 ;;
    --mirror)        MIRROR="$2"; shift 2 ;;
    --no-start)      START=0; shift ;;
    --no-forward)    FORWARD=0; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "未知参数: $1"; usage; exit 1 ;;
  esac
done

# ---------------- adb 目标 ----------------
if [ -z "$SERIAL" ]; then
  SERIAL="${HOST}:${PORT}"
  echo "[*] adb connect ${SERIAL}"
  "$ADB" connect "$SERIAL" || true
fi
ADB_CMD="$ADB -s $SERIAL"

# 等待设备上线
echo "[*] 等待设备 ..."
for _ in $(seq 1 30); do
  STATE=$("$ADB_CMD" get-state 2>/dev/null || true)
  [ "$STATE" = "device" ] && break
  sleep 1
done
[ "${STATE:-}" = "device" ] || { echo "[!] 设备未就绪（state=$STATE）"; exit 1; }

# ---------------- ABI 探测 ----------------
ABI=$("$ADB_CMD" shell getprop ro.product.cpu.abilist | tr -d '\r' | cut -d',' -f1)
case "$ABI" in
  arm64-v8a)      FRIDA_ABI="arm64" ;;
  armeabi-v7a)    FRIDA_ABI="arm" ;;
  x86_64)         FRIDA_ABI="x86_64" ;;
  x86)            FRIDA_ABI="x86" ;;
  *) echo "[!] 未知 ABI: $ABI"; exit 1 ;;
esac
echo "[*] 设备 ABI: $ABI → frida-server-$FRIDA_ABI"

# ---------------- 下载 ----------------
mkdir -p "$DOWNLOAD_DIR"
ARCHIVE="${DOWNLOAD_DIR}/frida-server-${FRIDA_VERSION}-android-${FRIDA_ABI}.xz"
BINARY="${DOWNLOAD_DIR}/frida-server-${FRIDA_VERSION}-android-${FRIDA_ABI}"

if [ ! -x "$BINARY" ]; then
  URL="${MIRROR}https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/frida-server-${FRIDA_VERSION}-android-${FRIDA_ABI}.xz"
  echo "[*] 下载 $URL"
  curl -L --fail --retry 3 -o "$ARCHIVE" "$URL"
  xz -dkf "$ARCHIVE"
  chmod +x "$BINARY"
else
  echo "[*] 复用已下载: $BINARY"
fi

# ---------------- push & 改名 ----------------
REMOTE="${REMOTE_DIR}/${ALIAS}"
echo "[*] push 到 ${REMOTE}"
"$ADB_CMD" push "$BINARY" "$REMOTE" >/dev/null
"$ADB_CMD" shell "chmod 755 $REMOTE"

# ---------------- 启动（root） ----------------
if [ "$START" = "1" ]; then
  echo "[*] 以 root 启动（监听 0.0.0.0:${LISTEN_PORT}）"
  "$ADB_CMD" root >/dev/null 2>&1 || true
  sleep 1
  "$ADB_CMD" shell "nohup $REMOTE -l 0.0.0.0:${LISTEN_PORT} >/dev/null 2>&1 &" || {
    echo "[!] 直接 root 启动失败，尝试 su -c"; 
    "$ADB_CMD" shell "su -c 'nohup $REMOTE -l 0.0.0.0:${LISTEN_PORT} >/dev/null 2>&1 &'";
  }
  sleep 2
  echo "[*] 进程状态:"
  "$ADB_CMD" shell "ps -A | grep $ALIAS" || echo "[!] 未检测到 $ALIAS 进程"
fi

# ---------------- 端口转发 ----------------
if [ "$FORWARD" = "1" ]; then
  echo "[*] adb reverse tcp:${HOST_FORWARD} tcp:${LISTEN_PORT}（宿主→设备）"
  "$ADB_CMD" reverse "tcp:${HOST_FORWARD}" "tcp:${LISTEN_PORT}"
fi

echo "[✓] 完成。宿主侧使用:"
echo "    frida -H ${HOST}:${HOST_FORWARD} -f net.crigh.hzkjsport -l your_script.js"
echo "    （-H 端口为宿主转发端口；直连设备则用 frida -H ${HOST}:${LISTEN_PORT}，需 adb forward）"
echo ""
echo "[i] 卸载: $ADB_CMD shell 'killall $ALIAS' && $ADB_CMD shell rm $REMOTE"
