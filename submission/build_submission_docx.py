# -*- coding: utf-8 -*-
"""生成「佐证材料（原创开源软件）」docx 草稿。"""
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Pt, RGBColor
from docx.oxml.ns import qn

OUT = r"C:\校园跑\open-source\campusrun-shield\submission\CampusRunShield-佐证材料-原创开源软件.docx"

doc = Document()
normal = doc.styles["Normal"]
normal.font.name = "Microsoft YaHei"
normal.font.size = Pt(10.5)
normal.element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")

for lvl in ("Heading 1", "Heading 2", "Title"):
    st = doc.styles[lvl]
    st.font.name = "Microsoft YaHei"
    try:
        st.element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    except Exception:
        pass


def para(text, bold=False, size=None, color=None, align=None):
    p = doc.add_paragraph()
    r = p.add_run(text)
    r.bold = bold
    if size:
        r.font.size = Pt(size)
    if color:
        r.font.color.rgb = RGBColor(*color)
    if align:
        p.alignment = align
    return p


def bullets(items):
    for it in items:
        doc.add_paragraph(it, style="List Bullet")


def kv_table(rows):
    t = doc.add_table(rows=0, cols=2)
    t.style = "Table Grid"
    for k, v in rows:
        cells = t.add_row().cells
        cells[0].text = k
        cells[1].text = v
        for c in cells:
            for p in c.paragraphs:
                for r in p.runs:
                    r.font.size = Pt(9.5)
    return t


title = doc.add_heading("2026 年开源安全奖励计划 · 佐证材料（原创开源软件）", level=0)
para("项目名称：CampusRun Shield · 校园跑安全盾", bold=True, size=12)
para("生成日期：2026-11-18", color=(0x66, 0x66, 0x66))

doc.add_heading("一、项目基本信息", level=1)
kv_table([
    ("开源软件名称", "CampusRun Shield · 校园跑安全盾"),
    ("开源软件地址", "（填校内 Git 仓库地址）https://git.hust.edu.cn/<用户名>/campusrun-shield"),
    ("赛道 / 类型", "原创开源软件项目赛道 / 工具软件（安全评估工具）"),
    ("开源许可证", "MIT License"),
    ("发布时间", "2026 年 11 月（符合 2021-09-01 之后）"),
    ("贡献者人数", "1（可扩展为团队）"),
    ("技术领域", "移动应用安全、Android 逆向、定位与传感器数据真实性、开源安全工具"),
])

doc.add_heading("二、项目简介", level=1)
para(
    "CampusRun Shield 是一个面向「校园跑/运动打卡类 App」的红蓝对抗开源工具集，"
    "以华中大体育（net.crigh.hzkjsport v1.1.0）为真实案例，同时交付："
    "①两种可复现的攻击测试床（无 root 真机 Gadget 注入的定位虚拟层；虚拟机 + 本地虚拟环境）；"
    "②五层纵深防护蓝图与可运行参考实现（服务端轨迹物理/地图匹配/传感器交叉校验、客户端反 mock/反模拟器/完整性自校验）；"
    "③攻击向量 × 防护有效性的对抗评估矩阵。项目回答的问题是：校园跑作弊能被拦在哪一层，以及如何用工程化手段把防线立起来。"
)

doc.add_heading("三、解决的真实问题", level=1)
bullets([
    "校园跑成绩与学分挂钩，作弊动机强、手段门槛低（虚拟定位、代跑、模拟器）。",
    "厂商防护长期黑盒：学生靠零散教程试错，学校难以评估自家 App 的真实防护水位。",
    "现有检测多为客户端单点（mock 标志、包名黑名单），可被 SDK 层注入轻松绕过。",
    "本项目把攻防两侧都做成开源、可复现、可度量的工程，供授权评估与教学使用。",
])

doc.add_heading("四、技术方案", level=1)
doc.add_heading("4.1 两种攻击测试床（offense/）", level=2)
bullets([
    "方式一（本机虚拟环境改造）：Frida Gadget 注入重打包 APK，在应用进程内 Hook 高德定位 SDK 的全部 AMapLocationListener 实现类，用轨迹播放器替换经纬度/速度/精度/时间戳/定位类型；支持圆形、跑道圆角矩形、自定义点列路线；mock 标志伪装。",
    "方式二（虚拟机 + 本地虚拟环境）：redroid/模拟器承载 App，叠加 root + frida-server + LSPosed + 模拟定位 + 传感器伪造 + 虚拟摄像头，可伪造步频与相机流。",
])
doc.add_heading("4.2 五层防护（defense/）", level=2)
bullets([
    "第 0 层 设备与环境检测：isFromMockProvider / AMapLocation.isMock / mock 应用黑名单 / 多源定位偏差 / maps·TracerPid·27042 注入特征 / 模拟器指纹 / 签名与 classes.dex CRC 自校验。",
    "第 1 层 采集加固：关键指标 native 计算 + HMAC-SHA256 结果签名，抬高 Hook 与改写成本。",
    "第 2 层 上报通道：签名 + nonce + 时间窗 + 证书绑定。",
    "第 3 层 数据真实性（决定性）：轨迹物理合理性、地图匹配、步频 × 步长与 GPS 速度交叉校验、环境一致性。",
    "第 4 层 群体与行为：轨迹指纹哈希、DTW 相似度聚类、同设备多账号、速度分布检验。",
    "第 5 层 证据链与处置：分段心跳 + 随机抽查 + 起终点人脸活体 + 阶梯式处置（标记→复核→处罚）。",
])

doc.add_heading("五、关键实测证据（真实案例）", level=1)
t = doc.add_table(rows=1, cols=3)
t.style = "Table Grid"
hdr = t.rows[0].cells
for i, h in enumerate(["实测项", "结果", "结论"]):
    hdr[i].text = h
rows = [
    ("重打包 + Gadget 注入后启动", "崩溃：RuntimeException: entry classes.dex is tamper", "壳（DA2Lock+libjdi VMP）对入口 dex 做完整性校验，方式一默认被拦截"),
    ("壳加载链分析", "AppComponentFactory0→AppStub1→libsys_misc/libstub000→libjdi 解密 CSN→落盘 dsn0/dex/libdsn_*.jar", "定位到解密 DEX 落盘路径，可用于脱壳/取证"),
    ("运行时检测点", "XposedBridge 检查、su 执行检查、fakelocation 包名检查（每页 onResume）、壳内 frida/xposed 字符串", "客户端已具备基础反注入/反环境能力"),
    ("业务判定规则", "瞬时超速段剔除（speed_too_fast）、步频异常=自行车作弊（dlg_step_rate_invalid）、距离过短拒提交、起终点人脸核验", "服务端/业务层已有交叉校验雏形"),
    ("防护参考实现自测", "服务端两校验器 demo 跑通：脚本轨迹与重放轨迹均判 reject；骑车样本命中 SUSPECT_VEHICLE", "防护规则可落地、可度量"),
]
for r in rows:
    cells = t.add_row().cells
    for i, v in enumerate(r):
        cells[i].text = v

doc.add_heading("六、可复现性", level=1)
para("防护侧（无需设备，直接跑）：", bold=True)
bullets([
    "python defense/server-side/trajectory_plausibility.py --demo",
    "python defense/server-side/run_evidence_validator.py --demo",
])
para("攻击侧（需自备测试设备/虚拟环境，仅限授权评估）：", bold=True)
bullets([
    "python offense/ondevice/gen_route.py --shape stadium --center LAT,LNG --track-length 100 --radius 18 -o campusrun_route.json",
    "apktool d / 注入 libcampusrun.so 与 config / smali 插入 loadLibrary / apktool b / uber-apk-signer 签名 / adb 安装",
    "docker compose -f offense/vm/docker-compose.yml up -d（方式二）",
])

doc.add_heading("七、工程结构", level=1)
for line in [
    "campusrun-shield/",
    "├── docs/            威胁模型、两种攻击方式、防护蓝图、对抗评估矩阵、路线图",
    "├── offense/         攻击端软件（ondevice 注入测试床 / vm 虚拟环境测试床）",
    "├── defense/         防护参考实现（server-side 校验器 / client-side 检测库 / native metric_guard）",
    "├── evaluation/      对抗评估数据",
    "└── README.md / LICENSE(MIT) / CONTRIBUTING.md",
]:
    para(line)

doc.add_heading("八、AI 辅助编程说明", level=1)
para(
    "本项目在研发过程中使用 DeepSeek Harness 多智能体协作（模型：DeepSeek-V4-Pro / DeepSeek-Flash）"
    "辅助完成：逆向分析取证、代码生成、文档撰写与交叉核对。"
    "所有产出经人工审核把关；AI 仅作为效率工具，技术判断与最终提交内容由作者负责。"
)

doc.add_heading("九、后续计划", level=1)
bullets([
    "补齐方式二（redroid）端到端实测与检测命中率统计。",
    "扩充对抗基准集，量化召回率与误报率。",
    "增加演示视频：伪造轨迹 → 服务端拦截 → 阶梯处置全流程。",
])

doc.add_heading("十、附件与链接（占位）", level=1)
bullets([
    "代码仓库：https://git.hust.edu.cn/<用户名>/campusrun-shield（占位，入围后迁移完整仓库）",
    "演示录屏：待补",
    "实测日志与截图：待补（脱敏后附）",
])

doc.save(OUT)

# 自检
d2 = Document(OUT)
print("paragraphs:", len(d2.paragraphs), "tables:", len(d2.tables))
print("saved:", OUT)
