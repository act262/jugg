# Bug Information Collection Template

Use this template only when the available evidence cannot explain the project-specific cause of a bug report. Never use it for a pure feature request. Adapt it to the actual investigation instead of copying placeholders or unsupported claims.

## Opening

Start with one short paragraph containing all of the following:

- The environment and reported scenario that were tested.
- What the reporter's existing evidence does or does not demonstrate.
- Which claimed reproduction steps have supporting logs or artifacts.
- The exact boundary of the available evidence. Do not describe an unverified claim as a reproduced result.
- What project-specific condition remains unknown and why further evidence is needed.

Example structure:

> The supplied evidence shows [observable warning or behavior] in [reported environment], but does not establish [missing cause or transition]. No build, reproduction, or device command was run during this read-only investigation. More project evidence is required to identify the responsible module relationship, resource structure, build step, or runtime cause.

## Evidence Options

Offer these two executable options. Replace every placeholder in the selected launch template with the real Issue URL and the most specific key error supported by the available evidence.

### 1. Upload Jugg Diagnostics

Recommend this first:

1. In Android Studio, double-press Shift and run `Report Jugg Issue`.
2. Select `Upload logs` and reply to the Issue with the Report ID.
3. If upload is unavailable, select `Save locally without uploading` and attach the generated Diagnostics Bundle ZIP to the Issue.

### 2. Run a Local Read-Only Agent Investigation

Give the reporter one short, directly copyable prompt. The linked English guide limits the Agent to reading the existing project evidence and producing an analysis; it does not permit builds, reproductions, project changes, ADB, or other device commands.

Use this built-in template when the reporter's primary language is Chinese:

```text
请在当前 Android 工程中调查以下 Jugg 异常，基于现场证据尽可能定位根因。

相关 Issue：
[ISSUE_URL]

关键错误：
[KEY_ERROR]

必读指引：
https://raw.githubusercontent.com/tencentmusic/jugg/main/.agents/skills/issue-handler/references/local-agent-investigation-guide.md

限制：只读现有证据；不要修改工程、运行构建或复现，也不要执行 ADB 或其他设备命令。
```

Use this built-in template for all other reporters:

```text
Investigate the following Jugg failure in the current Android project and identify the root cause as far as the available evidence allows.

Related Issue:
[ISSUE_URL]

Key error:
[KEY_ERROR]

Required guide:
https://raw.githubusercontent.com/tencentmusic/jugg/main/.agents/skills/issue-handler/references/local-agent-investigation-guide.md

Restriction: Read existing evidence only. Do not modify the project, run builds or reproductions, or execute ADB or other device commands.
```

## Formatting Requirements

- Keep the opening concise and evidence-based.
- Present diagnostics upload and local read-only Agent investigation as two alternatives.
- Use exactly one built-in launch template according to the reporter's primary language. Do not translate, expand, summarize, or inline the linked guide.
- Keep both built-in templates in this file so the Issue reply does not depend on ad-hoc translation.
- Put the selected launch template in a fenced `text` code block, not a blockquote.
- Keep every URL complete and exposed on its own line inside the prompt code block. Do not use `[label](url)` Markdown links there.
- Do not leave placeholders in the published reply.
- Never ask the Agent to upload a Demo, logs, or analysis report automatically.
- Do not request evidence already supplied and inspected.
