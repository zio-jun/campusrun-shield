# 文档索引 · Documentation Index

> 建议阅读顺序：01 → 10（总矩阵）→ 按需进 11–15 专题；08 为文献支撑。

## 基础

| 文档 | 内容 |
|---|---|
| [01 威胁模型](01-threat-model.md) | 资产、攻击者画像、攻击面、STRIDE |
| [08 相关工作](08-related-work.md) | 定位欺骗 / 轨迹检测 / 传感器指纹 / 反注入 / 校园跑制度文献（含论文、专利、开源项目） |
| [09 技术报告](09-technical-report.md) | 论文式：问题—方法—结果—局限 |

## 攻击侧（思路级，可复现实验）

| 文档 | 内容 |
|---|---|
| [02 方式 A](02-offense-method-a.md) | 本机注入重打包的攻击链与边界 |
| [03 方式 B](03-offense-method-b.md) | 虚拟机 + 本地虚拟环境 |
| [03b 方式 C](03b-offense-method-c.md) | 容器虚拟环境与"环境检测缺失"根因 |
| [08 攻击手册](08-attack-playbook.md) | 完整攻击链的操作级记录（受控实验） |

## 防御侧（库 + 文档）

| 文档 | 内容 |
|---|---|
| ⭐ [10 攻防全景矩阵](10-defense-matrix.md) | **总索引**：8 层 50 种攻击模式 × 检测 × 防御 × 绕过后下一步 × 残留风险 |
| [11 壳与注入对抗实录](11-shell-and-injection.md) | 实测对抗：签名校验、资产完整性、落盘防删、守卫自刀、注入面 |
| [12 Frida/Hook 检测](12-anti-frida-anti-hook.md) | 六类检测技术 + 绕过与反绕过 |
| [13 定位伪造对抗](13-location-anti-spoof.md) | mock provider 至坐标转换的逐环防御 |
| [14 服务端判定算法](14-server-side-validation.md) | 证据校验、距离重算、形态学、配速模型、图谱风控 |
| [15 纵深防御与验收](15-defense-in-depth.md) | 成本模型、验收清单、运营手册、合规声明 |
| [16 sentinel 工具手册](16-sentinel-cli.md) | CLI 子命令、输入格式、判定逻辑、样例报告 |

## 规划与结构

| 文档 | 内容 |
|---|---|
| [04 防护蓝图](04-defense-blueprint.md) | 五层纵深防护设计 |
| [05 对抗评估](05-evaluation.md) | 攻击向量 × 防护有效性矩阵 |
| [06 路线图](06-roadmap.md) | 里程碑 |
| [07 开源结构](07-repo-structure.md) | 交付物组织方式 |
