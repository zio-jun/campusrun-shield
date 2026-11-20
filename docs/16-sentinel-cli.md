# 16 · sentinel 评估工具使用手册

> 配套上一节 README 的「评估工具」章节；本手册给出每个子命令的输入格式、
> 判定逻辑与样例输出（样例文件见 `examples/`）。

## 安装

```bash
# 方式一：源码运行（零依赖，无需安装）
python -m sentinel demo

# 方式二：pip 安装（提供 sentinel 命令）
pip install -e .
sentinel --help
```

## 一、apk —— 加固/壳与可疑资产识别

```bash
python -m sentinel apk examples/sample-hardened.apk -o examples/apk-report.md
```

**输入**：任意 APK 文件（只读，不反编译、不执行）。

**判定**：按 `sentinel/rules/shells.json` 的特征库匹配条目名（厂商特征），
并对加密资产（`csn*.data`、`*.vd`）与驻留原包（`assets/*.apk`）加分。

| 等级 | 评分 | 含义 |
|---|---|---|
| none | 0 | 未识别到加固特征 |
| light | 1–4 | 轻加固 / 单厂商特征 |
| medium | 5–7 | 多厂商特征或加密资产 |
| strong | ≥8 | 强加固（多特征 + 加密资产 + 驻留原包） |

样例输出（`examples/apk-report.md`）：

```
| 厂商 | 证据特征 |
|---|---|
| 顶象 DxShield | com.security.shell, libapk0000.so, libstub000.so |

**综合等级：strong（评分 10）**

## 可疑资产
- `assets/csn8783f650.data`
```

## 二、trace —— 轨迹可信度分析

```bash
python -m sentinel trace examples/trace_fake.csv -o examples/trace_fake-report.md
python -m sentinel trace examples/trace_real.csv -o examples/trace_real-report.md
```

**输入**：CSV（表头 `t,lat,lng`；`t` 为毫秒时间戳）或 JSON（`{"points":[{t,lat,lng},...]}`）。

**指标**：

| 指标 | 含义 | 伪轨迹特征 |
|---|---|---|
| 速度变异系数 CV | 速度标准差/均值 | < 5%（过于匀速） |
| 速度熵 | 速度分桶熵 | 偏低（分布集中） |
| 钳制占比 | 贴近同一速度值的点比例 | > 40% |
| 等间隔占比 | 时间间隔贴近中位数比例 | > 90%（机器生成） |
| 折角数 | 相邻段方位角变化 > 45° | 占比过高（直线拼接） |
| 加速度突变 | 相邻速度差 > 3 m/s | 次数偏多 |

**评分**：0–100，≥40 视为可疑，≥65 高风险。样例：真人轨迹 0 分，回放轨迹 65 分。

## 三、evidence —— 成绩证据校验

```bash
python -m sentinel evidence examples/evidence.json -o examples/evidence-report.md
```

**输入**：

```json
{
  "distance": 3000.0,
  "duration": 1200.0,
  "points": [{"t": 1700000000000, "lat": 30.51, "lng": 114.41}, ...]
}
```

**检查项**：

| 编号 | 检查 | 失败后果 |
|---|---|---|
| E001 | 轨迹点数 ≥ 10 | 证据不足 |
| E002 | 里程一致（重算偏差 ≤ 2%） | 里程造假 |
| E003 | 时间戳单调、跨度 > 0 | 证据损坏 |
| E004 | 用时一致（误差 ≤ max(30s, 5%)） | 用时造假 |
| E005 | 平均速度 1~10 m/s | 速度异常 |
| E006 | 瞬时最大速度 ≤ 10 m/s | 存在瞬移 |

**结论**：`pass`（全过）/ `warn`（1–2 项失败）/ `reject`（≥3 项失败）。

## 四、bundle —— 合并总报告

```bash
python -m sentinel bundle --apk examples/sample-hardened.apk \
  --trace examples/trace_fake.csv --evidence examples/evidence.json \
  -o examples/bundle-report.md
```

三类结果合并为一份 Markdown（见 `examples/bundle-report.md`），可直接作为
安全评估报告附件。

## 五、demo —— 样例全流程

```bash
python -m sentinel demo --dir examples
```

生成真人轨迹、回放轨迹、证据包三个样例并打印评估结果，用于快速验证安装。

## 六、退出码约定

| 场景 | 退出码 |
|---|---|
| 正常完成 | 0 |
| 参数错误 | 2（argparse 默认） |
| 文件不存在/损坏 | 非 0（异常栈） |

## 七、扩展指引

- 新加固厂商：编辑 `sentinel/rules/shells.json`（增加 markers 与 weight）；
- 新轨迹指标：在 `sentinel/mock_trace.py` 增加纯函数并在 `_score` 中加权；
- 新证据规则：在 `sentinel/evidence.py` 增加 `rep.add("Exxx", ...)`。
- 所有扩展都要补 `tests/` 用例（CI 会跑）。
