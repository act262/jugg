---
title: First run
description: Master the Jugg first run process to establish a Gradle baseline, verify console incremental signals, and understand everyday 3-second hot reloads.
status: active
tags:
  - onboarding
  - run
---

# First run

After installing the Jugg plugin and waiting for project Sync to complete, you can trigger your first run. The core goal of the first run is to **build a full APK and establish the incremental compilation baseline and deployment state**. This initial run takes about the same time as a standard Gradle build.

## 1. Pre-run checklist

Before clicking Run, quickly confirm the following:

1. **Run Configuration**: Selected `jugg:<moduleName>` in the top toolbar (rather than the native App configuration).
2. **Target Device**: Connected a physical device or emulator running Android 8.0 (API 26) or later.
3. **Sync Status**: Android Studio has no Gradle Sync or background indexing tasks in progress.

Once confirmed, click the green **Run** button in the Android Studio toolbar. If you selected the wrong device or configuration, stop the run at any time by clicking the red Stop button.

## 2. First-run mechanics: Establishing the Gradle baseline

On the first run, Jugg initializes automatically via the following pipeline:

```text
Click Jugg Run
  -> Check local environment and device connection status
  -> Detect missing incremental baseline, automatically fall back to full Gradle build
  -> Install and launch the target application
  -> In the background, collect class relations, resource maps, and R files for future incremental runs
  -> Baseline ready: subsequent code and resource modifications enter the 3-second bypass pipeline
```

> [!NOTE]
> A full Gradle build is only required on the very first run. Once the baseline is established, everyday development changes will experience instant second-level feedback.

## 3. Verification: Console log characteristics

Upon successful completion of the first run, the **Run** tool window at the bottom of Android Studio displays Jugg's progress:

```text
[Jugg] Starting Jugg Run for app...
[Jugg] Baseline not found, falling back to full Gradle build...
[Jugg] Gradle build succeeded. Installing APK...
[Jugg] Jugg baseline established successfully. Ready for incremental builds!
```

When you see the baseline readiness message, Jugg has successfully taken over incremental compilation for the project.

## 4. Everyday development: 3-second hot reload and execution paths

After the baseline is established, whenever you make small changes to Java, Kotlin, XML layouts, or Assets, continue clicking **Jugg Run** (or press `Ctrl + R` / `Shift + F10`):

| Modification Scenario | Jugg Decision Path | Expected Feedback Latency |
|---|---|---|
| **Modify method body / add private method** | Incrementally compiles classes and applies hot swap | **~1–3 seconds** (no app restart needed) |
| **Modify XML layout / drawables** | Incrementally generates resource table and pushes to app sandbox | **~2–3 seconds** (refreshes immediately) |
| **Modify class signature / add Activity** | Incrementally repackages APK and restarts target Activity | **~3–5 seconds** |
| **Modify `build.gradle` / dependencies** | Automatically falls back to full Gradle build safely and refreshes baseline | Depends on project full build duration |

## 5. When to fall back to Gradle manually

In the following scenarios, manually refreshing the baseline with a native Gradle build is recommended:

| Scenario | Recommended Action |
|---|---|
| **Manually cleaned `build/` directory** | Incremental artifacts are missing; run a native App Run to rebuild the baseline |
| **Switched Git branch or upgraded dependencies** | Major dependency or Manifest changes; perform a full build once |
| **Validating unsupported annotation processors** | Verify generated code correctness with a native Gradle build first |

---

## Next steps

Your baseline is successfully established! Continue exploring Jugg's powerful developer capabilities:

- 📖 **[Everyday run & hot reload guide](../guide/run.md)**: Master hot reload modes and multi-device deployment
- ⚡ **[Jugg CLI practical guide](../guide/cli.md)**: Experience 3-second builds in terminal or automation scripts
- 🛠️ **[Supported packaging methods](../troubleshooting/supported-packaging.md)**: Explore support for obfuscation, AabResGuard, and Dynamic Features
