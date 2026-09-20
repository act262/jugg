# AndroidFastRun 实现方案

> 创建：2026-09-20  
> 状态：P0 已批准并在独立仓库落地（本地验证）  
> 性质：新项目实现方案。代码参考 Jugg，核心用 Kotlin Native 编成桌面可执行文件。P0 仓库目录：工作区 `/AndroidFastRun`（独立 Git，不是 Jugg 子模块）。

---

## 0. 冻结决策

| 决策 | 口径 |
|---|---|
| 项目 | 独立仓库 **AndroidFastRun**，不是 Jugg 子模块。行为与流程参考 Jugg，不复制 `deploy_compat`、IntelliJ `PlatformApi` 桩、Python CLI。 |
| 核心形态 | **Kotlin Native 可执行文件 `afr`。** 目标：`macosArm64`、`macosX64`、`linuxX64`、`mingwX64`。同一二进制既是 CLI，也是插件调用的引擎。 |
| 核心语言 | Kotlin（Native）。不把 kotlinc/D8/R8 链进 Native 进程。编译器用 **系统 JDK + 外部进程**（`kotlinc` / `d8` / `aapt2` / `gradlew` / `adb`）。 |
| 插件如何依赖核心 | 插件是 JVM，**不能** `implementation` Native klib。插件通过 **执行 `afr` + JSON 协议**（或本机 MCP）调用核心能力。可选一个极薄的 JVM `protocol` 模块只解析 JSON，不含编译器。 |
| 运行时 JDK | 核心二进制本身不需要 JRE。增量编译、D8、Gradle 回退使用 **系统 JDK 11+**（`JAVA_HOME` 或 PATH 的 `java`）。不内嵌 JRE。 |
| UI | 只在 IDE 插件。核心无图形界面。 |
| 首期不做 | Android Studio Apply Changes deployer、多 AS 版本 compat、Jugg Debug attach、Python CLI、把 Jugg `main` 整库迁进来。 |

产品表述：**`afr` 是桌面 Native 引擎；命令行直接跑；Android Studio 插件只画 UI 并调用 `afr`。**

---

## 1. 为什么不能把 Jugg `main` 编成 Native

Jugg 的 `main` 在进程内加载 kotlinc、D8、ASM、ddmlib、IntelliJ 桩。这些是 JVM 字节码，Kotlin Native 链不上。IDE 插件也必须加载 JVM 类。

AndroidFastRun 换一种切法，才能同时满足「核心是 Native 可执行文件」和「插件调用核心」：

| Jugg | AndroidFastRun |
|---|---|
| 插件进程内嵌 JVM 核心库 | 插件 `exec afr` |
| 进程内 `KotlinCompilerInvoker` / D8 API | Native 编排，`Process` 调 kotlinc、`java -cp r8.jar D8` |
| `AsDeployerCompat` Apply Changes | `adb` + overlay 目录（参考 Jugg Direct Overlay） |
| Python `jugg.py` 再连 MCP | `afr` 自己就是 CLI |

Jugg 仍可作为行为参考：增量优先、Gradle 回退、init script 读项目信息、不改业务工程源码注入 runtime、失败给明确原因。

---

## 2. 仓库结构

```text
android-fast-run/
  core/                     Kotlin Native 应用（CLI + 引擎）
    src/nativeMain/kotlin/
      cli/                  argv、console=plain|json
      command/              compile / deploy / status / serve
      project/              项目模型、路径、基线
      compile/              增量编排、调用外部工具链
      deploy/               adb、overlay、install
      gradle/               写 init script、调 gradlew
      protocol/             JSON 请求/响应（与插件共用契约）
  protocol/                 可选：同一套 kotlinx.serialization 模型
                            native + jvm 双目标，插件只依赖 jvm 产物
  plugin/                   IntelliJ / Android Studio 插件
    src/main/kotlin/        Run Configuration、Tool Window
    src/ide_entry/          稳定入口，只调 AfrClient
  runtime/                  App 内 runtime（可先复用/裁剪 Jugg agent 思路）
    gradle-init/            注入 Bootstrap 的 init script 模板
    agent/                  JVMTI so + instruments（仍是 C++/Java 产物，由 core adb push）
  docs/
  gradle/                   构建 Native 与插件
```

构建：

- `core`：`kotlin("multiplatform")`，仅 Native application，产出 `afr` / `afr.exe`。
- `plugin`：`intellij` 插件，打包时按平台带上对应 `afr` 二进制到 `lib/bin/`。
- 不把 `core` 当插件的 `implementation` 依赖。

---

## 3. 核心能力与调用链

### 3.1 CLI（核心自己跑）

```text
afr [--project-dir P] [--console=plain|json] [--device SERIAL] <command>
```

首期命令：

| 命令 | 行为 |
|---|---|
| `version` | 只读 Native 版本，不启 JDK |
| `status` | 基线、变更文件、设备 |
| `compile` | 增量编译，必要时 Gradle 回退 |
| `deploy` | 编译并部署 |
| `gradle-build` | 强制完整 Gradle |
| `restart` | `adb` 启停包名 |
| `serve` | 本机 MCP，给 Agent / 插件长连接 |

无 JDK 时 `version` 仍可用；`compile`/`deploy`/`gradle-build` 失败并提示 JDK 11+。

### 3.2 插件调用核心

```text
用户点 Run / 面板按钮
  -> plugin AfrClient
  -> 解析插件资源或 PATH 中的 afr（按 OS/Arch）
  -> Process: afr --project-dir <basePath> --console=json compile|deploy
  -> 读 stdout JSON（status/message/logPath/artifacts）
  -> 面板与 Run 窗口只展示，不实现编译
```

约束：

- 同一 `projectDir` 同时只允许一个 `afr` 写状态（锁文件 `build/afr/engine.lock`）。插件与终端共用。
- 长任务：`serve` 起来后走 MCP `jobId` 轮询；短路径也可同步阻塞并流式把 stderr 打到 Run 窗口。
- 插件不内嵌第二套编译逻辑。

### 3.3 增量编译（参考 Jugg，改为进程编排）

```text
afr compile
  -> 若无基线：gradlew assembleXxx -I afr-readProjectInfo.gradle.kts
  -> 扫描变更（相对基线 source index）
  -> 按模块顺序：
       kotlinc/javac 进程（classpath 来自 Gradle 快照）
       java -cp <r8.jar> com.android.tools.r8.D8 ...
       aapt2 增量（可先整包资源，P2 再 aapt2 compile 单文件）
  -> 产物写入 build/afr/out/
  -> 失败且允许回退：gradlew
```

工具定位：

- JDK：`JAVA_HOME`
- Android SDK：`ANDROID_HOME` / `local.properties`
- kotlinc：优先工程 Kotlin dist，否则 SDK/内置编译器路径（从 Gradle 快照读）
- D8/R8：Gradle 快照里的 AGP R8 classpath（Jugg 已有同类读取，init script 可参考）
- aapt2：SDK `build-tools`

Kotlin Native 只做参数拼接、进程、超时、日志。不加载编译器 JAR。

### 3.4 部署（参考 Jugg Direct，不接 Apply Changes）

```text
afr deploy
  -> compile
  -> adb devices；ANDROID_SERIAL 或唯一在线设备
  -> 需要改包：adb install -r
  -> class/resource：push 到 code_cache overlay（协议对齐 Jugg DirectOverlayWriter 思路）
  -> 可选 push JVMTI agent；冷启动加载 overlay
```

不做多版本 `AsDeployerCompat`。热更成功率可以低于 Jugg Apply Changes，失败则明确报错或回退 install。

### 3.5 无侵入 runtime（参考 Jugg Bootstrap）

完整 Gradle 时由核心写出 init script，经 `-I` 注入：

- runtime jar 进 variant
- 合并 Manifest 换成 Bootstrap Application，原类名进 meta-data

init script 是文本，Native 只负责落盘和传给 `gradlew`。runtime/agent 仍用 Java/C++ 构建，作为资源随 `afr` 分发，不是 Native 源码的一部分。

---

## 4. 从 Jugg 参考什么、丢掉什么

**参考（行为与算法，重写成 Kotlin Native / 脚本）：**

- 增量优先 + Gradle 回退
- Gradle init script 读 module/variant/classpath（`readProjectInfo.gradle.kts` 思路）
- Direct overlay 写入与 overlay id
- Manifest Application 替换 + 启动期恢复
- MCP 工具作为 CLI 的另一门面（协议可简化，不必一次搬 18 个工具）

**丢掉（IDE/JVM 绑定）：**

- `idea/` Run tool window 内部编译、`JuggRunningTask` 进程内调用
- `deploy_compat/*`、Apply Changes、XDebugger
- `platform_compat` IntelliJ 桩
- Python `jugg.py`
- 进程内 D8 API、K2JVMCompilerIsolate
- Control Panel 迁入核心

**后期再评估：** Hilt transform、DataBinding 增量、Compose resource、Flutter/C++ external、androidTest、混淆 mapping。首期只做 debug 单模块/常规 app 的 Java/Kotlin 方法体修改 + 资源整包或简单 overlay。

---

## 5. 协议（插件与 CLI 共用）

`--console=json` 成功/失败都走 stdout 一条 JSON，进度走 stderr。

```json
{
  "status": "success|failed|running",
  "message": "...",
  "jobId": null,
  "logPath": "build/afr/log/latest.log",
  "data": { "isCompileSuccess": true, "isDeploySuccess": false }
}
```

字段名稳定后，插件 `AfrClient` 与 `afr` 各自解析。若维护成本高，再抽 `protocol` 的 jvm/native 双编译，仍然 **没有** 编译器代码进插件。

---

## 6. 分期

### P0 骨架

- 新建仓库、Gradle KMP、`afr version` 在三平台（至少 macOS arm64 + linux x64 + mingw 交叉或 CI）跑通。
- 空插件能找到并执行 bundled `afr version`，把 JSON 打到 Event Log。

### P1 CLI 编译

- init script 最小项目信息（applicationId、source、classpath、assemble task）。
- `afr compile`：无基线 Gradle；有变更则 kotlinc/javac + D8 进程。
- `afr status`。
- 系统无 JDK 时失败文案。

### P2 CLI 部署

- `adb install` / overlay push / restart。
- Gradle 注入 runtime 的最小 Bootstrap（可先只重启生效，不做 JVMTI redefine）。

### P3 插件

- Run Configuration：内部只调 `afr deploy`。
- 简单 Tool Window：状态、最后一次 JSON message、打开日志。
- 不复制 Jugg Control Panel 全套。

### P4 对齐 Jugg 的增强

- JVMTI redefine、资源 inclink、MCP `serve`、更多子命令。

每期单独批准后再写代码。

---

## 7. 验证

| 行为 | 证据 |
|---|---|
| `afr version` 无 Python、无 JDK 可运行 | 精简 PATH 下启动 |
| `afr compile` 依赖系统 JDK | `JAVA_HOME` 有效成功；无效明确失败 |
| 插件不实现编译 | 插件模块无 kotlinc/D8 调用；只有 `AfrClient` |
| demo app 改一行 Kotlin 后 `afr deploy` | 真机/模拟器可见变化（P2） |
| 插件 Run 与终端 `afr deploy` 同一锁 | 并行时一方失败或等待 |

不把 Jugg 的 `:idea:test` 当本项目回归。AndroidFastRun 自建最小 demo 与定向测试。

---

## 8. 风险

| 风险 | 处理 |
|---|---|
| 外部 kotlinc/D8 参数与 Jugg 进程内不一致 | 首期只承诺 debug 方法体；参数从 Gradle 快照生成，失败回退 Gradle |
| Windows 进程与路径 | Native mingw 单独验收引号与 `java.exe` |
| 插件带三份二进制体积 | 按当前 OS 在构建插件时只打入一个 `afr` |
| overlay 无 JVMTI 时不生效 | P2 文档写明需重启；P4 再 agent |
| 从 Jugg 复制过多 JVM 代码 | 只参考算法与脚本，Native 侧重写 |

---

## 9. 待确认

1. ~~仓库放独立 GitHub 项目，还是先在本工作区建 `android-fast-run/` 目录？~~ **已确认：** 工作区独立目录 `/AndroidFastRun`，本地先验证；暂不挂 Jugg 子模块、不改 Jugg 生产代码。
2. ~~插件目标 Android Studio 版本下限？~~ **已确认：** 跟当前最新稳定 Android Studio **Quail 4 Patch 1**（`2026.1.4.8`，build `AI-261.26222.65.2614.16379836`，平台 `261.26222.65` / IU `2026.1.4`）。`sinceBuild=261.26222`，`untilBuild=261.*`。不做多 AS compat。
3. P1 是否包含 Gradle 回退，还是先只做「有基线才能增量」？（推荐 P1 含回退，否则没法第一次跑。）

已冻结：核心 Native 可执行文件；插件 exec 调用；系统 JDK；不内嵌 JRE；不把 Jugg `main` 编成 Native；P0 插件目标 AS Quail 4 Patch 1。

---

## 10. 第一期（P0）落地记录

- 独立仓库：`/AndroidFastRun`（Jugg `.gitignore` 忽略该目录）。
- `afr version`：Kotlin Native `linuxX64` 本机可运行；声明 `macosArm64` / `macosX64` / `mingwX64`。
- 空插件：`AfrClient` exec bundled `afr --console=json version`，JSON 写入 Event Log。
- 不改 Jugg 生产代码。

**明确本期不建：** 完整编译器、插件 Run、runtime 注入、从 Jugg 大批量搬 Kotlin/JVM 源码。

---

## 11. 关联

- Jugg：`docs/ai_knowledge/01_architecture.md`、`98_code_map.md`、`03_runtime_jvmti.md`、`docs/wiki/zh/concepts/jugg-runtime.md`
- 先前 Jugg 宿主方案（JVM 核心 + Native CLI 转发）与本方案不同：本方案 **核心即 Native 引擎**，编译走外部进程，不是 `java -jar jugg-engine`。
