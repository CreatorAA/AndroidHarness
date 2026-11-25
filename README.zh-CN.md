<div align="center">

<img src=".github/assets/icon.svg" alt="AndroidHarness 标志" width="128">

# AndroidHarness

[English](README.md) | **简体中文**

**在安卓手机上聊天、写代码，也能让助手跨应用办事。**

无需电脑，也不要求 root。

这是 CreatorAA 基于 [Sanuu7 的 AndroidHarness](https://github.com/Sanuu7/AndroidHarness) 开发的分支。

本分支的 APK **只通过 [GitHub Releases](https://github.com/CreatorAA/AndroidHarness/releases) 下载和更新**。如果发布页还没有 APK，可以[从源码构建](#从源码构建)。

[![调试版构建状态](https://github.com/CreatorAA/AndroidHarness/actions/workflows/nightly.yml/badge.svg)](https://github.com/CreatorAA/AndroidHarness/actions/workflows/nightly.yml)
[![支持 Android 8.0 及以上版本](https://img.shields.io/badge/Android-8.0%2B-3ddc84)](https://developer.android.com/about/versions/oreo)
[![许可证：MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

## 与上游 4.1 有什么不同

本分支的版本号为 **4.1.0-custom**，基于 Sanuu7 的 [4.1 版本（`v4.1-release`）](https://github.com/Sanuu7/AndroidHarness/releases/tag/v4.1-release)，对应提交 [`ccf421f7`](https://github.com/Sanuu7/AndroidHarness/commit/ccf421f73518642acc8f2d454b7e8e53805c8a86)。

- **手机助手从「看屏幕」扩展到「操作应用」。** 上游已经可以在助手面板里询问屏幕内容。本分支新增独立的手机助手（Phone）对话：在同一项目里再次唤起助手，会接着上次的对话。助手可以通过应用公开的操作、内置无障碍服务，或选装的 AutoJs6 来办事。你可以授权它托管**当前任务**，包括发送或删除；运行命令或脚本仍需确认。所有项目合计保留最近使用的 10 段手机助手对话，不挤占聊天或代码对话的历史。
- **普通话可全程离线听写。** 上游支持 Android 系统语音和云端识别。本分支内置普通话模型：可以从第一个字开始就离线识别，也可以在系统识别中断后接着听写；已识别的文字会留在草稿里。
- **图表更容易读准。** 上游已经支持交互图表。本分支为数值轴加上刻度标签，让相近的读数也能区分；在从右到左的界面里，点选的数据与图上的位置也能对齐。
- **聊天备份保留更多历史信息。** 备份勾选图片后，只在旧版回答中出现的图片也会保存。恢复记忆时，会一并保留原有的文件夹结构。
- **由你决定何时更新。** 不再沿用上游回到前台时检查更新、在应用内安装新版的流程。打开「设置 → 关于 → GitHub Releases」即可查看本分支的发布页，自行选择是否下载安装。

手机助手上手指南：[简体中文](docs/phone-assistant-zh.md) | [English](docs/phone-assistant.md)

## 项目简介

AndroidHarness 是用 Kotlin 和 Jetpack Compose 编写的原生安卓应用。助手可以直接读写项目文件，在手机上的 Linux 工具链里运行 Bash、Git、Python 和 Node.js，操作浏览器，再把改动推送到 GitHub。每一步都会显示在对话里，方便你随时查看。

按手头的事选择模式：

- **代码（Code）**：在项目目录里编写和修改文件、执行命令、使用 Git、跑测试，或借助浏览器核实结果。
- **聊天（Chat）**：适合日常问答、查资料、写作和讨论想法。使用只读工具；如果聊着聊着需要动手做项目，可以把对话交给代码模式。
- **手机助手（Phone）**：不离开当前应用就能唤起。它可以接着上次的话题，打开指定应用，或分几步在手机上完成一件事。你可以保留逐项确认，也可以只托管本次任务。[查看手机助手指南](docs/phone-assistant-zh.md)

内置模型服务无需注册或填写密钥；也可以下载模型，在手机上完全离线运行。

## 界面预览

<table>
  <tr>
    <td align="center" width="20%"><img src=".github/assets/showcase/01-code.jpg" alt="代码助手正在构建应用" width="100%"><br><sub>代码助手执行任务</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/02-chat.jpg" alt="聊天回答中的内容卡片" width="100%"><br><sub>聊天中的富内容回答</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/03-providers.jpg" alt="模型服务商选择器" width="100%"><br><sub>切换模型服务商</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/04-code-installation.jpg" alt="请求安装 Linux 软件包的审批卡片" width="100%"><br><sub>安装前先确认</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/05-code-options.jpg" alt="代码对话的工具与模式菜单" width="100%"><br><sub>选择工具和模式</sub></td>
  </tr>
  <tr>
    <td align="center" width="20%"><img src=".github/assets/showcase/06-settings.jpg" alt="应用设置主页" width="100%"><br><sub>设置</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/07-code-settings.jpg" alt="代码模式的行为设置" width="100%"><br><sub>代码模式设置</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/08-plugins.jpg" alt="插件管理界面" width="100%"><br><sub>插件与技能</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/09-9router.jpg" alt="手机上的 9Router 本地模型路由页面" width="100%"><br><sub>本机运行 9Router</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/10-local-models.jpg" alt="设备上的本地模型列表" width="100%"><br><sub>离线模型</sub></td>
  </tr>
</table>

## 主要能力

- **直接在项目里干活。** 改文件、使用 Shell 和 Git、运行测试、操作浏览器，或把部分工作交给子代理。点开工具调用卡片，就能查看具体操作与结果。
- **随时问屏幕上的内容。** 将 AndroidHarness 设为系统数字助手，无需切换应用即可提问。如果模型不能看图，可以先在手机上识别图中文字。
- **让长任务接着做。** 用 `/goal` 设定目标、子任务和预算。助手会持续推进，由独立检查判断是否完成；中断后可以从已保存的进度继续。
- **使用真正的 Shell。** 支持 Shizuku、无线 ADB、可选的 root 和基于 Termux 的 Linux 环境；未安装其他工具时使用 toybox。执行前会检查命令，工具输出中可识别的敏感信息会被遮盖。
- **不填密钥也能开始。** 内置服务无需注册，本地 GGUF 模型可离线运行。云端 API 密钥和 ChatGPT 账号均为可选。
- **语音输入与朗读。** 内置的 Edge 语音无需密钥，提供 300 多种微软语音，每种都可试听。还可选用 Groq、OpenAI、ElevenLabs、Deepgram、Cartesia 或 AssemblyAI，具体支持听写、朗读还是两者兼有，取决于服务商。
- **按需选择模型。** 支持 Anthropic、Google Gemini、ChatGPT 账号及 OpenAI 兼容接口。可设置备用模型，在首选模型出错时切换。
- **搜索后读原文。** 可选 11 个搜索服务，助手会选择并在失败时切换，阅读排名靠前的页面，返回相关段落。可以标记依据不足的说法，也可以针对同一问题继续深入查证。
- **扩展工具。** 插件可来自 npm、GitHub、Claude 插件市场、压缩包或手机目录，也能让助手代为管理。支持 Claude Code 风格的插件命令、可执行文件和语言服务器，以及通过 stdio 或 HTTP 连接的 MCP 服务。
- **后台继续执行。** 前台服务、恢复卡片、持久化消息队列和可选的自动继续，帮助任务应对熄屏和断网。
- **手机操作由你授权。** 任务开始前可以先审阅。托管只覆盖这一次任务，包括发送或删除等操作，不再逐项询问；命令与脚本仍需确认。
- **22 种界面语言。** 可随时切换，无需重启应用。

## 继承自上游 4.1 的功能

- **跨应用屏幕问答。** 将应用设为数字助手，就能对当前屏幕语音或打字提问。不支持视觉输入的模型也可借助本机文字识别读取图片。
- **持续执行目标。** `/goal` 支持轮次上限、可选检查命令、预算、命名子任务和每轮变更摘要。恢复任务时保留进度；启用「继续改进」后，达到目标也可以继续打磨。
- **备份与首次设置。** 备份可流式写入单个文件，按需选择对话，并包含图片和用量统计。首次设置按页引导，可恢复聊天和设置，也可以暂不授予文件权限。
- **整理记忆。** 支持彩色文件夹、触发词、只在触发时使用的记忆，以及批量移动或删除；可缩放关系图查看记忆之间的联系。
- **Exa 搜索。** 除已有的 10 个搜索服务外，还可以让 Exa 按语义搜索并返回网页全文。
- **对话界面改进。** 浮动任务条显示当前工作，斜杠菜单在输入框旁跟随光标。可以编辑已发送消息中的图片和文件，在后台截取浏览器画面，并逐项决定信任哪些工作区工具。

完整的上游更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 开始使用

1. [从源码构建](#从源码构建)，或在[本分支的发布页](https://github.com/CreatorAA/AndroidHarness/releases)有 APK 时下载安装。以后要覆盖更新，请使用以同一密钥签名的新版 APK。
2. 按需授予文件权限。「所有文件访问权限」允许 Shell 和文件工具使用实际路径；暂不授权时，应用使用自己的目录，任务需要读取你的文件时再询问。
3. 选择模型。可以直接使用内置的免密钥服务，在设置中添加 API 密钥，或到「设置 → 本地模型」下载模型。
4. 如需完整的命令行环境，推荐在应用内安装 Linux 环境。需要设备级 Shell 权限时，可安装 [Shizuku](https://github.com/RikkaApps/Shizuku)，或在「设置 → 终端与设备」配对无线 ADB。已 root 的设备也可在这里启用直接 root 访问。

打开工作区，让助手试着改一处代码；不需要项目时，直接开始聊天即可。工具表现异常时，可以运行 `/doctor` 逐类检查。

## 功能一览

下面按使用场景列出常用功能。完整说明见 [FEATURES.md](FEATURES.md)。

**代码**
- 流式显示回答、思考区、工具卡片和文件差异；验证卡片列出实际执行过的构建与测试。
- 一个工作区对应一个目录。项目可以组合多个工作区，并分别设置可编辑或只读。
- 支持规划模式，也可以让一个模型规划、另一个执行。支持子代理和持久化消息队列；长按发送可选择何时让助手读取排队中的消息。
- 目标可以跨轮推进，支持子任务、轮次上限、检查命令、预算和完成后继续改进。
- 在对话中审批需要设备权限的命令、设置闹钟或计时器，或定时发送消息。
- 自动化任务可按天、按小时或手动运行提示词。

**聊天**
- 通过 11 个服务检索网页、阅读原文段落并引用来源，支持核查论断和调整研究深度。
- 设置提醒、闹钟和计时器；粘贴照片，在统一的附件栏管理文件，或打开全屏图库。
- 自定义聊天助手的指令、模型、工具和知识文件。
- 回答可以包含表格、图表、示意图、图片或交互预览。
- 与代码模式共享记忆，支持收藏回答、语音输入和朗读。

**手机助手**
- 在其他应用上方唤起助手，询问眼前的内容，或交代一项操作。助手可使用 Android 为本次任务共享的屏幕文字和图像。
- 模型不能看图时，可以在设备上识别图片中的文字。

**文件、编辑器与 Git**
- 文件管理器和多标签编辑器，支持语法高亮、查找替换和离线语言工具。
- 并排对比文件差异、按对话跟踪并撤销编辑，在「构建与测试」面板查看结果。
- 在应用内登录 GitHub、导入仓库、提交、推送或创建拉取请求。
- 连接 SSH 工作区，通过 FTP 或 SFTP 同步文件。

**模型**
- 内置免费服务，也支持 ChatGPT 账号、Anthropic、Gemini 和 OpenAI 兼容 API。
- 每个服务商最多可轮换使用 20 个 API 密钥，支持代理、自定义请求头、密钥测试和备用模型。
- 为听写与朗读分别选择服务商，包括提供 300 多种语音的免费 Edge 朗读。
- 在设备上运行 GGUF 模型，按需启用 GPU 加速，也可以使用 9Router 搭建本地模型路由。

**个性化**
- 自定义配色、背景画布和触感反馈，另有实验性的玻璃效果。
- 设置按功能分区，可以自建分区；长列表支持滚动动态模糊。
- 提供手机和桌面布局，支持键盘快捷键。

## Shell 权限

命令在哪里执行，由目标、工作区和访问设置共同决定。

| 命令目标 | 执行身份 | 可用环境 |
| --- | --- | --- |
| 需要设备权限的命令 | 通过 Shizuku 使用 Shell UID | 与 `adb shell` 类似的系统访问权限 |
| 无 Shizuku 时的设备命令 | 通过无线 ADB 使用 Shell UID（2000） | 与 `adb shell` 类似的系统访问权限 |
| 明确请求 root 的命令 | 经验证的 root 服务，UID 0 | 已启用 root 时可完整访问设备 |
| 工作区命令 | 应用自身的 UID | 包含 Bash、Git、Python 和 Node.js 的 Linux 工具链 |
| 未安装其他环境时 | 应用自身的 UID | toybox 的 `sh` |

root 不会自动启用。更多细节见[终端与 Shell](FEATURES.md#terminal-and-shells)。

## 常见问题

**需要电脑吗？**

不需要。编辑、Shell、Git、GitHub、SSH、运行模型，乃至构建和签名 APK，都可以在手机上完成。

**必须有 API 密钥吗？**

不必。内置服务免注册，可直接使用免费模型；本地模型也不需要密钥。只有你选用的云端服务要求时才需要配置。

**手机必须 root 吗？**

不必。Shizuku 或内置的无线 ADB 配对可提供 Shell 级权限，无需解锁引导加载程序。已经 root 的手机也可以主动启用 root 功能。

**能离线使用吗？**

本地模型下载后即可离线运行。搜索、读取网页以及访问 GitHub 需要联网。

**支持哪些界面语言？**

英语、简体中文、繁体中文、俄语、阿拉伯语、波斯语、巴西葡萄牙语、德语、西班牙语、日语、印度尼西亚语、僧伽罗语、越南语、土耳其语、马来语、泰语、法语、印地语、韩语、泰米尔语、孟加拉语和菲律宾语。

**对话和密钥会发到哪里？**

你选用的模型会收到对话内容。网页搜索等工具也可能向你启用的服务发送数据。服务商凭据加密保存在设备上，不要把密钥贴进聊天或要分享的文件。只有你明确选择时，设置备份才包含 API 密钥；ChatGPT 登录凭据不会写入备份。

**助手可能改坏文件吗？**

有可能。文件工具留有记录的编辑可以审阅和撤销；Shell 命令、插件或手机操作也可能更改不在该记录内的数据。留意授予的权限，重要文件请做好备份。

**支持哪些设备？**

Android 8.0 及以上版本。本地模型需要兼容的 64 位设备。

## 从源码构建

构建流程使用 Temurin JDK 21。构建包含原生代码的完整版本时，请安装 Android SDK Platform 37、NDK 27.2.12479018 和 CMake 3.22.1。

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

本地模型可以直接使用 CPU，无需单独下载运行时。如果构建的 APK 需要下载可选的 Vulkan 后端，请使用[与版本匹配的 GPU 包](repowiki/74-build-ci-and-testing.md#building-with-the-published-gpu-runtime)。

启用[调试版工作流](https://github.com/CreatorAA/AndroidHarness/actions/workflows/nightly.yml)后，`main` 上的应用代码变更、夜间计划或手动触发都可以构建开发版 APK。Actions 产物是开发构建，不是正式发行版。完整环境说明见[贡献指南](CONTRIBUTING.md)。

## 配置 GitHub

在「设置 → GitHub」中选择「使用 GitHub 登录」或「使用个人访问令牌」。

- 设备登录会显示一段验证码，用浏览器打开 GitHub 并输入即可。
- 只有需要编辑 GitHub Actions 工作流文件时才开启工作流权限。
- 使用细粒度令牌时，请选中目标仓库并授予 **Contents: read and write**。编辑工作流还需要 **Workflows** 权限。
- 组织仓库可能另需管理员批准或 SSO 授权。

导入仓库需要先安装 Linux 环境。克隆的仓库存放在应用私有目录，无需授予「所有文件访问权限」。推送不会强制覆盖远端分支，应用会对照远端分支检查推送的提交。

自动推送失败时，可打开运行历史查看原因：常见情况有工作区选错、未安装 Git、远端使用 SSH、令牌权限不足、远端出现新提交，或分支保护阻止了推送。修正原因后，可点「推送已有提交」重试。

<details>
<summary>使用自己的 GitHub OAuth 应用构建</summary>

注册 [GitHub OAuth 应用](https://github.com/settings/developers)，开启 **Enable Device Flow**，然后在不纳入版本管理的 `local.properties` 中设置 `GITHUB_CLIENT_ID`。本地构建的 debug 和 release 版本都会带上该值；仓库源码、文档及公开工作流不包含实际的 Client ID。此方式不使用 Client Secret，也不需要自建认证服务器。未配置 Client ID 的构建仍支持令牌登录；共享 OAuth 应用的设备授权流程有 GitHub 限流，建议保留令牌作为备选。

</details>

## 文档

- [完整功能说明](FEATURES.md)
- [配置指南](docs/configuration/README.md)和 `harness.json` 的 [JSON Schema](docs/harness.schema.json)
- [插件指南](docs/configuration/plugin-manager.md)
- [开发者 Wiki](repowiki/README.md)：代理引擎、工具、模型服务、MCP 和工作区架构
- [上游更新记录](CHANGELOG.md)、[隐私政策](PRIVACY.md)和[安全说明](SECURITY.md)

## 参与贡献

欢迎报告问题，也欢迎提交修复、测试和文档。请先阅读[贡献指南](CONTRIBUTING.md)，报告缺陷或提出功能建议时使用相应的 Issue 表单。参与讨论和开发请遵守[行为准则](CODE_OF_CONDUCT.md)。界面翻译可通过 [POEditor](https://poeditor.com/join/project/ZPbHNa9WWc) 参与。

## 致谢

感谢 Sanuu7 开发了[原版 AndroidHarness](https://github.com/Sanuu7/AndroidHarness)。[上游 Discord](https://discord.gg/NdC5K3XeWF) 和[捐助页面](https://buymeacoffee.com/sanuj7)均由原项目维护。

离线普通话听写使用阿里巴巴的 Paraformer 模型，由 crazyant 量化、csukuangfj 打包为 sherpa-onnx 可用的版本。[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 通过 [ONNX Runtime](https://github.com/microsoft/onnxruntime) 运行模型，[Silero VAD](https://github.com/snakers4/silero-vad) 负责检测人声。模型与转换版本的来源见[随包分发的许可声明](app/src/main/assets/licenses/offline-speech.txt)。手机操作还可以选配 [AutoJs6](https://github.com/SuperMonster003/AutoJs6) 和它的 [MCP Server 插件](https://github.com/SuperMonster003/AutoJs6-Plugin-MCP-Server)。

AndroidHarness 也使用或借鉴了以下项目：

[Hermes Agent](https://github.com/NousResearch/hermes-agent)、
[Aider](https://github.com/Aider-AI/aider)、
[pi (ohmypi)](https://github.com/earendil-works/pi)、
[OpenCode](https://github.com/anomalyco/opencode)、
[Claude Code](https://github.com/anthropics)、
[Roo Code](https://github.com/RooCodeInc/Roo-Code)、
[Cline](https://github.com/cline/cline)、
[browser-use](https://github.com/browser-use/browser-use)、
[Termux](https://github.com/termux)、
[Shizuku](https://github.com/RikkaApps/Shizuku)、
[CodeGraph](https://github.com/colbymchenry/codegraph)、
[Caveman](https://github.com/JuliusBrussee/caveman)、
[llama.cpp](https://github.com/ggerganov/llama.cpp)、
[sora-editor](https://github.com/Rosemoe/sora-editor) 和
[Eruda](https://github.com/liriliri/eruda)。

## 许可证

本项目采用 [MIT 许可证](LICENSE)。
