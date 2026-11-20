# 12 · Frida / Hook 检测技术（检测原理 → 绕过 → 反绕过）

> 本文系统整理运行时注入检测技术。原则：**任何单点检测都可绕，检测的目标是把绕过成本堆到攻击者放弃**。
> 每个小节给出：原理、代码要点（Kotlin/C 伪码）、绕过方式、反绕过。

## 1. 模块与内存检测

### 1.1 /proc/self/maps 模块白名单
- 原理：枚举当前进程加载的 so 路径，与官方清单比对；出现未知 so（改名 gadget、xposed 模块）即可疑。
- 绕过：内存加载（不落盘）、`/proc` 隐藏（magisk）、memfd 加载。
- 反绕过：同时扫 `/proc/self/map_files`、`dl_iterate_phdr` 结果交叉比对；隐藏模块仍会在 map_files 留下 inode 痕迹。

### 1.2 内存字符串扫描
- 原理：在自身可读段与常见注入库地址范围内搜 `frida`、`gadget`、`gum-js`、`XposedBridge`、`re.frida.server` 等特征。
- 绕过：gadget 重编译去字符串、运行时擦除。
- 反绕过：扫描不限于字符串：gum 结构体、Interceptor 跳板特征（常见 hook 库的 inline hook 代码形态）。

### 1.3 段属性与页保护异常
- 原理：inline hook 常需要 mprotect 改页属性；扫描自身代码段是否存在 RWX/异常 RW 页。
- 绕过：hook 后恢复页属性。
- 反绕过：与"函数首字节基线"交叉（1.4）。

## 2. 函数完整性自检

### 2.1 首字节基线比对
- 原理：关键函数（签名校验、坐标转换、落库 setter）的首 N 字节在编译期固化为基线，运行时比对；被 inline hook 后跳板字节必变。
- 绕过：hook 函数中部/尾部；用插桩不破坏头部的方式（ART 层 hook）。
- 反绕过：基线抽查点分散 + 抽查自身逻辑也被混淆；比对本应发生在 native 且不可被自身 hook 截获（直读指令 + 独立校验线程）。

### 2.2 ArtMethod 入口比对
- 原理：native 遍历关键 Java 方法的 ArtMethod，比较 entry_point_from_quick_compiled_code 与 dex 定义一致性，识别 Frida Java.use 替换。
- 绕过：不 hook 方法入口，hook 内部调用点；或恢复 entry 后延时 hook。
- 反绕过：多时刻采样（hook 恢复有窗口）；关键方法 native 化（无 ArtMethod 可 hook）。

### 2.3 JNIEnv 函数表校验
- 原理：JNIEnv 表关键项（ThrowNew、RegisterNatives、FindClass…）应指向 libart 导出地址；被改表即注入痕迹。
- 绕过：不劫持表，改用 artmethod 替换等。
- 反绕过：与 2.2 组合。

## 3. 调试与 ptrace 检测

- 原理：`TracerPid`、`/proc/self/status`、ptrace(PTRACE_TRACEME) 抢占、`/proc/self/task/*/status`、JDWP 端口。
- 绕过：反 ptrace 循环、修改内核/隐藏模块。
- 反绕过：多信号联合判断——TracerPid 可被伪造，但"单步执行计时膨胀"与"异常断点行为"难伪造；结合心跳（服务端看调试时段的异常静止）。

## 4. 行为侧检测（最难绕过）

- **调用耗时分布**：hook 引入明显开销（Java 桥/跳板），关键方法 P50/P99 耗时基线外的异常即告警。
- **调用频率异常**：被 hook 的 getter（如 AMapLocation.getLatitude）调用频率突变（hook 内缓存逻辑会改变频率形态）。
- **数据一致性**：getter 返回值与对象内部字段不一致（native 读字段 vs 调 getter 交叉验证）。
- **心跳与自毁**：防御逻辑按周期心跳；被 hook 阻断后心跳缺失 → 服务端降权。

## 5. 环境级检测组合拳

| 层 | 检测 | 单点绕过成本 |
|---|---|---|
| 文件 | maps/map_files 白名单、fd 指向可疑文件 | 低（隐藏模块） |
| 内存 | 字符串/段属性/入口字节 | 中（擦除/恢复） |
| 系统 | JNI 表、ArtMethod 入口、TracerPid | 中（换 hook 方式） |
| 行为 | 耗时分布、频率形态、数据一致性 | 高（需还原原始形态） |
| 网络 | 心跳、行为脉冲、设备-账号图谱 | 高（需伪造全链路） |

## 6. 结论性建议

1. 检测结果**分档**（正常/可疑/高危），不二值化：二值判断会被逐条打靶。
2. 每条检测独立上报，服务端聚合评分，**不做客户端即时自毁**（即时自毁暴露检测点）。
3. 关键业务逻辑 native 化 + 冗余计算（Java 与 native 各算一遍对账），使 hook 单一入口无效。
4. 接受"客户端必被控"的假设，把终局防线放在服务端证据校验（docs/14）。
