# 贡献指南

## 项目结构约定

| 目录 | 职责 | 修改前请确认 |
|---|---|---|
| `docs/` | 设计与评估文档 | 与代码行为保持同步 |
| `offense/ondevice/` | 方式一：Gadget 注入 + 定位虚拟层 | 新增能力需在 docs/02 补说明 |
| `offense/vm/` | 方式二：虚拟机测试床 | 环境脚本必须参数化（不要硬编码本机路径） |
| `defense/server-side/` | 轨迹/证据校验算法 | 新增规则需给出 demo 数据与预期输出 |
| `defense/client-side/` | 客户端检测库 | 注释标注对应攻击方式（A/B） |
| `evaluation/` | 对抗矩阵与指标 | 结论必须可追溯到实测或代码 |

## 提交规范

- Commit message：`feat|fix|docs|eval(scope): 简述`
- PR 描述需包含：动机、改动点、如何验证（命令/截图/数据）
- 涉及检测阈值的改动，附带 false positive 影响说明

## 测试要求

- Python 代码：`python -m py_compile <file>` + `--demo` 可跑通
- Kotlin/C++：给出可编译说明（Android Studio / NDK），无工程依赖的以单文件示例为原则
- Shell/编排脚本：参数使用 `PLACEHOLDER` 占位，不包含任何真实密钥与本机绝对路径

## 边界

- 攻击端代码仅用于授权评估与自建测试环境
- 不接收针对真实生产账号的利用代码、不接收未经脱敏的用户数据
- 案例研究中引用的第三方密钥/标识一律脱敏或标注"公开泄露样本"
