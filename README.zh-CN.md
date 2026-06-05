# Git AI Studio —— IntelliJ IDEA 插件

[English](README.md) | 简体中文

把本地 AI 代码归因洞察搬进 **JetBrains IDE**,基于外部 [`git-ai`](https://github.com/git-ai-project/git-ai) CLI。
它是 [git-ai-studio](https://github.com/bujueyunjian/git-ai-studio) 桌面版的 IDE 同胞 —— 同一套解析、
同样的零上传 / 零 telemetry 承诺,现在住在编辑器旁的工具窗口里,还多了一项桌面版做不到的 IDE 原生能力:
**编辑器行号槽里的逐行 AI 归因。**

> 所有解析都经 `git-ai` CLI 在本机完成。零数据上传,无账号,无云。

## 能做什么

- **提交归因(Stats)** —— 单提交 AI / 人工 / 未知 行级三桶、工具·模型分布、合并与 note 提示。
- **Dashboard** —— 跨仓 / 单仓的 AI 归因时间线(按时间范围),含每日分桶。
- **People** —— 按作者的归因细分(AI 占比、提交数、下钻)。
- **Notes** —— `refs/notes/ai` 归因日志查看(prompts / sessions / attestations)。
- **诊断与安装** —— git-ai 是否就绪 / 版本、环境健康(`git-ai debug`)。
- **编辑器内行级归因(gutter)** —— 对当前文件跑 `git-ai blame --json`,在行号槽标出每行:
  **AI → 紫,你 → 蓝**(与桌面墨宠同一把锁死的颜色不变量,"形象即数据")。编辑器右键 → **Toggle AI Attribution (Git AI)**。

## 架构

这是一次 Tauri → IntelliJ 的移植,**整套复用 git-ai-studio 的 React 前端**,而非重写:

```
┌──────────────────────────────────────────────┐
│ IntelliJ 工具窗口(右侧停靠)                  │
│  ┌────────────────────────────────────────┐  │
│  │ JBCefBrowser(内置 Chromium)            │  │   原样复用:
│  │   git-ai-studio React UI                │  │   recharts · shadcn/ui
│  │   Dashboard / Stats / People / Notes …  │  │   Tailwind v4 · i18next
│  └────────────────────────────────────────┘  │
└───────────────┬──────────────────────────────┘
                │  JS 桥(window.__gitaiSend / __gitaiReceive)
                ▼
   Kotlin CommandDispatcher  ──shell──▶  git-ai / git(LC_ALL=C,--json)
                │
                └─ 编辑器 gutter 归因(原生 LineAnnotation) ─▶ git-ai blame --json
```

- **换传输层即复用 UI。** 桌面前端通过 `@tauri-apps/*` 调 Tauri 后端;这里用 Vite `resolve.alias`
  把每个 `@tauri-apps/*` 导入重定向到极薄的 JCEF 桥 shim(`webview/src/bridge/*`),React 业务源码
  **一行都不用改**。`invoke` / `listen` / `emit` 走 JS 桥;桌面专属(updater / 托盘 / 自启 / 悬浮宠物)变 no-op。
- **Kotlin 取代 Rust。** `CommandDispatcher` 通过 shell 调 `git-ai` / `git`,参数、flag、超时与桌面版后端完全一致
  (git-ai 上游是 schema / 指标 / 阈值的唯一权威)。
- **主题跟随 IDE。** 明暗由 IDE 主题驱动(不是 Web 应用自己):Kotlin 在加载时及每次 LAF 变化时切 `.dark` class。
- **gutter 是原生的。** 独立于 webview —— 用 `TextAnnotationGutterProvider` 画逐行归因,边写代码边看 AI / 人工。

## 环境要求

- IntelliJ IDEA(或其它 JetBrains IDE)**2024.3+**,JetBrains Runtime 含 JCEF(默认即含)。
- `PATH` 上有 [`git-ai`](https://github.com/git-ai-project/git-ai) CLI(或指定其路径;插件还会探测
  `~/.local/bin`、`~/.cargo/bin`、`/opt/homebrew/bin`、`/usr/local/bin`)。
- 构建需 JDK 21。

## 构建与运行

```bash
# 1) 先构建复用的 Web UI(产物输出到 src/main/resources/web)
cd webview && pnpm install && pnpm build && cd ..

# 2) 在沙箱 IDE 里运行插件(若 webview 已 install,Gradle 会自动重建 Web UI)
./gradlew runIde

# 或打成可分发 zip
./gradlew buildPlugin   # → build/distributions/git-ai-studio-idea-<版本>.zip
```

随后打开右侧的 **Git AI Studio** 工具窗口。

## 现状(v1)

已完整接通(真实 `git-ai` / `git` 数据):仓库解析与选择、最近提交、提交归因与工作区状态、
单仓与跨仓历史、People 细分、区间汇总、git-notes 列表/详情、单提交 changed-files 与 AI 行、分支、
任意 ref 的文件列表/读取、blame、whoami、raw show、生效 ignore patterns、设置持久化、目录选择器,
以及**编辑器 gutter 归因**。

诚实的后续项(v1 里返回明确错误而非伪造数据):流式 git-ai 安装 / hook 配置、daemon 修复、
Claude 设置合并、checkpoints/mock、日志查看、诊断页的逐 agent hook 探测(当前渲染 `git-ai debug` 报告)。
stats 为会话内内存缓存(桌面版用 SQLite)。

## 许可

[MIT](LICENSE)。独立 OSS 项目,未与 Git AI 商业团队 affiliate —— 只消费开源 `git-ai` CLI 与公开的
`refs/notes/ai` 标准。
