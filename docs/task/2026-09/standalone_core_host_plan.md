# 可脱离 IDE 的 Jugg 核心宿主方案

> 创建：2026-09-17  
> 修订：2026-09-20（CLI 为 Kotlin Native；不使用 Python；不内嵌 JRE；核心库运行在系统 JDK 上）  
> 状态：待评审（未开始实现）  
> 性质：架构实现方案。冻结决策在第 0 节；分阶段范围在第 6 节。未批准前不改生产代码。

---

## 0. 冻结决策（实现期不再重开）

| 决策 | 口径 |
|---|---|
| 分层 | **除 IDE 相关以外，业务在 JVM 核心库 `main`。** IDE 插件只做 UI 与 Android Studio 集成，调用 `main`。核心库运行在 Java 环境中。 |
| IDE 相关（`idea`） | 面板/对话框/Tool Window/Run Configuration 编辑器、Gradle Sync、Apply Changes deployer、XDebugger、插件热更新。 |
| 用户可见 CLI | **Kotlin Native 跨平台可执行文件 `jugg`。** 不使用 Python，不用 Python 包装。入口进程本身不要求 Python。 |
| 核心库运行时 | **使用系统提供的 JDK。** 不内嵌 JRE。无 IDE 时由 Native CLI 用 `JAVA_HOME` / PATH 上的 `java` 启动 JVM 引擎（`jugg-engine` jar）。缺 JDK 时明确失败并提示，不下载、不分发 JRE。 |
| 不能把 `main` 编成 Native | kotlinc、D8/R8、ddmlib、Gradle API 都是 JVM 生态。IDE 插件必须加载 JVM 字节码。`main` 保持 JVM 库；Native 不实现第二套编译器。 |
| 命令所有权 | **语义只在 `main` 的 `JuggCommands`。** Native `jugg` 只做 argv、输出、进程发现：连 IDE MCP，或用系统 `java` 拉起引擎。 |
| JDK 版本 | 引擎与 `main` 一致，要求系统 JDK **11+**（当前 `jvmTarget=11`）。解析顺序：`JAVA_HOME`，否则 PATH 上的 `java`。 |
| 工程 Gradle | `gradle-build` / 基线仍走项目 `gradlew`，用该工程自己的 JDK 配置。 |
| UI | 只在 IDE 插件层。无独立图形窗口。 |
| 独立部署 | 无 IDE 时不走 Apply Changes；走 adb install / Direct overlay。 |
| 单写者 | 同一 `projectDir` 只允许一个引擎写状态。IDE 已打开则 Native CLI 只转发 MCP，不另起引擎。 |

产品表述：**面板画在 IDE，按钮调 JVM 核心库。`jugg` 是 Kotlin Native 二进制；编译部署仍在系统 JDK 上跑同一套核心库，不随包装内嵌 JRE。**

---

## 1. 背景与现状

当前 CLI 是 Python，必须本机有 Python 3.7+，并且通常要连已启动的 IDE MCP。

仓库没有 Kotlin Native 模块。`main`、`idea`、`cmd_line` 都是 Kotlin/JVM。

约束：

- IntelliJ 插件只能依赖 JVM 库。
- 编译器链路无法搬进 Kotlin Native。
- 用户环境已有 JDK（Android 开发者标配），不必由 Jugg 携带 JRE。

---

## 2. 目标与非目标

### 2.1 目标

1. `jugg` 以 Kotlin Native 发布 macOS / Linux / Windows 可执行文件，不要求 Python。
2. `JuggCommands` 只在 `main` 实现一次；IDE UI、Native CLI、MCP 都调用它。
3. 无 IDE 时用系统 `java` 启动 `jugg-engine`；有 IDE 时只转发 MCP。
4. 删除 Python 命令实现；**不内嵌、不下载 JRE。**

### 2.2 非目标

- 用 Kotlin Native 重写 kotlinc / D8。
- GraalVM native-image 整包 `main`。
- 安装包内带 JRE / JDK。
- 独立 Jugg 图形界面。

---

## 3. 目标架构

```text
  Kotlin Native：jugg（PATH 入口，无 Python）
  argv / console / 端口探测
           │
           ├─ IDE 已初始化该项目 ──HTTP MCP──► idea 进程内 JuggCommands
           │
           └─ 否则：系统 java -jar jugg-engine.jar
                    └── main.JuggCommands / Session / MCP
                    要求 JAVA_HOME 或 PATH 中的 JDK 11+

  idea 插件（JVM）
    UI / Sync / Debug / Apply Changes
         └── 直接依赖 main（进程内）
```

Native 层没有 compile 实现。无 IDE 时用系统 JDK 跑引擎，而不是打开 Android Studio，也不是解压内嵌 JRE。

### 3.1 模块

| 模块 | 形态 | 职责 |
|---|---|---|
| `main` | Kotlin/JVM 库 | Session、`JuggCommands`、编译部署、Model、MCP server |
| `idea` | IDE 插件 | UI + AS 集成，依赖 `main` |
| `cmd_line` | JVM 引擎 `jugg-engine.jar` | 无 UI 宿主：`HostPlatformApi` + `JuggCommands`，由系统 `java` 启动 |
| **新建 `cli_native`** | Kotlin Native exe `jugg` | 参数、输出、发现 MCP、`exec` 系统 `java` 启动引擎 |

Native 目标（P1）：`macosArm64`、`macosX64`、`linuxX64`、`mingwX64`。

### 3.2 Native CLI 行为

```text
jugg [--console=plain|rich|json] [--project-dir P] [--if-compiling wait|interrupt] <subcommand>
  -> 扫描 12320..12329
  -> 命中已初始化的 P：MCP tools/call
  -> 未命中：解析 JDK（JAVA_HOME/bin/java，否则 PATH 的 java）
       校验 11+；失败则报错退出
       java -jar <install>/engine/jugg-engine.jar --project-dir P
       引擎起 MCP 后 CLI 再走同一 HTTP
```

引擎按 projectDir 用锁文件复用进程。`jugg serve` 显式保持引擎，供 Agent 连接。

`jugg version` 只打印 Native CLI 版本，不启动 JVM。`compile` / `deploy` 在无 IDE、无可用 JDK 时失败，文案指出需要 JDK 11+。

### 3.3 IDE UI

```text
Panel 按钮 -> idea Controller -> JuggCommands（main）-> Model
```

对话框留在 `idea`。核心不弹窗。

### 3.4 无 IDE 部署

引擎内：`AdbDeployTargetManager` + Direct overlay / adb install。不调用 `AsDeployerCompat`。

---

## 4. 关键设计取舍

### 4.1 不用 Python

入口用 Native 二进制，避免第二套运行时和参数解析。

### 4.2 `main` 保持 JVM

编译器与 IDE 插件都在 JVM 上。命令只实现一次。

### 4.3 不用内嵌 JRE

Android 开发机已有 JDK。携带 JRE 增加体积和更新成本。引擎就是普通 `java -jar`，依赖系统 JDK 11+。

### 4.4 不用 GraalVM native-image

与把 `main` 编成 Native 同类问题；系统 JDK 已足够跑引擎。

---

## 5. 对现有代码的改造方向

1. `main` 抽 `JuggCommands`；IDE 与引擎都调用它。
2. 新增 `cli_native`，对齐现网 CLI 参数与 MCP 映射。
3. `cmd_line` 作为 `jugg-engine.jar`，不附带 JRE。
4. 安装与 `JuggCliAutoUpdater` 分发 Native `jugg` + engine jar，不再安装 Python。
5. 删除 Python 命令实现。

---

## 6. 分期落地

### P0 JVM 命令层

- `JuggCommands` + Session；MCP / Panel 委托它。
- 验证：现有 MCP、Model 测试仍绿。

### P1 Native `jugg` + 系统 JDK 引擎

用户可观察结果：PATH 上是 Native `jugg`，无 Python；有 IDE 时走 MCP；无 IDE 时用系统 JDK 启动引擎完成 `compile` / `status` / `gradle-build` / `version` / `devices`。

- `cli_native` 四平台二进制。
- `jugg-engine.jar` + 系统 `java` 拉起。
- 技能安装改为 Native `jugg` + engine jar。

验证：无 Python 的 `jugg version`；无 IDE 且 `JAVA_HOME` 指向 JDK 11 的 `jugg compile`；无 JDK 时失败文案明确；IDE 打开时不双写。

### P2 无 IDE 部署 + 其余公开命令

- 引擎走 Direct/ADB `deploy` 及其余 MCP 工具。
- 验证：demo 设备部署。

### P3 收口

- CI one-shot 并入引擎。
- 删除 Python CLI；同步文档与 skill version。

---

## 7. 验证策略

| 行为 | 证据 |
|---|---|
| Native `jugg version` 不需要 Python | 无 python 的 PATH 下启动 |
| 无 IDE 编译需要系统 JDK | `JAVA_HOME` 有效时成功；去掉 java 后失败并提示 11+ |
| 命令语义与 MCP 一致 | L1 MCP action；Native 测探测/转发/输出 |
| IDE 占用时不双写 | L1 或脚本 |
| IDE Run / 面板 | 现有 L3 |

---

## 8. 风险

| 风险 | 处理 |
|---|---|
| 本机 JDK 过旧或只有 JRE | 启动前读 `java -version`，低于 11 明确失败 |
| 多 JDK 时选错 | 只认 `JAVA_HOME` 与 PATH 第一项，不扫描系统 |
| Native 四平台 CI / 签名 | P1 先出二进制；签名另开 |
| Agent 仍写 `python3 jugg.py` | PATH 提供 `jugg`；skill 升 version |

---

## 9. 待确认问题

1. 无基线时引擎是否自动一次完整 Gradle？（推荐是，使用工程自己的 JDK。）
2. P1 是否必须含无 IDE 引擎拉起？（推荐是，否则仍只能当 IDE 遥控器。）

设备：`ANDROID_SERIAL`，否则仅一台在线设备时选用。

已冻结：不内嵌 JRE；核心库用系统 JDK 11+；不用 Python 包装。

---

## 10. 第一期（P0+P1）预期改动范围（待批准）

**模块**

- `main`：`JuggCommands`、Session；MCP/Controller 委托。
- 新建 `cli_native`：Kotlin Native `jugg`。
- `cmd_line`：`jugg-engine.jar` 入口。
- 技能安装：Native `jugg` + engine jar，不装 Python，不装 JRE。

**明确不做**

- Python wrapper、内嵌 JRE。
- 把 `main` 改成 Kotlin Native。
- 迁移 Control Panel 到 `main`。
- Apply Changes / Debug attach 进引擎。

**新文件（预期）**

- `cli_native/`
- `main/.../JuggCommands.kt`
- 引擎锁文件（`~/.jugg/engine` 或 `JuggPathManager`）

---

## 11. 关联文档

- `docs/ai_knowledge/01_architecture.md`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/04_engineering_compat.md`
- `docs/ai_knowledge/08_mcp_design.md`
- `docs/ai_knowledge/08_cli_tools_list.md`
- `docs/wiki/zh/guide/cli.md`
- `docs/wiki/zh/concepts/mcp-and-cli.md`
