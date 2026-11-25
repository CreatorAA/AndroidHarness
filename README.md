<div align="center">

<img src=".github/assets/icon.svg" alt="AndroidHarness logo" width="128">

# AndroidHarness

**English** | [简体中文](README.zh-CN.md)

**Chat, write code, and get things done across apps on your Android phone.**

No PC needed. No root needed.

CreatorAA’s fork of [AndroidHarness by Sanuu7](https://github.com/Sanuu7/AndroidHarness).

This fork uses [GitHub Releases](https://github.com/CreatorAA/AndroidHarness/releases) as its only APK download and update channel. If no APK is listed, [build from source](#build-from-source).

[![Debug build](https://github.com/CreatorAA/AndroidHarness/actions/workflows/nightly.yml/badge.svg)](https://github.com/CreatorAA/AndroidHarness/actions/workflows/nightly.yml)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)](https://developer.android.com/about/versions/oreo)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

## Changes from upstream 4.1

This branch identifies itself as **4.1.0-custom**. Its base is Sanuu7’s [4.1 release (`v4.1-release`)](https://github.com/Sanuu7/AndroidHarness/releases/tag/v4.1-release), commit [`ccf421f7`](https://github.com/Sanuu7/AndroidHarness/commit/ccf421f73518642acc8f2d454b7e8e53805c8a86).

- **Phone conversations and actions.** Upstream already lets you ask about the screen from its assistant panel. Here, the panel resumes a separate Phone conversation for each project. It can operate other apps through public actions, the built-in accessibility service, or an optional AutoJs6 companion. Hands-free approval applies to one task, including sending or deleting; commands and scripts still need approval. The ten most recently used Phone conversations are kept across projects, separately from Chat and Code.
- **Offline Mandarin dictation.** Upstream offers Android and cloud speech recognition. This fork bundles a Mandarin model: choose offline-only dictation from the first word, or let it take over when Android’s recognizer stops. Text already transcribed stays in your draft.
- **Charts in rich replies.** Upstream already supports interactive charts. This fork adds labeled value axes that distinguish close readings and keeps chart selection aligned in right-to-left layouts.
- **Chat backups.** When images are included, backups also keep images referenced only by an earlier answer version. Restoring saved memories preserves their folder structure.
- **Manual app updates.** Instead of upstream’s foreground update check and installer, **Settings → About → GitHub Releases** opens this fork’s release page. You choose whether to download and install an APK.

Phone assistant guide: [English](docs/phone-assistant.md) | [简体中文](docs/phone-assistant-zh.md)

## About

AndroidHarness is a native Android app, written in Kotlin with Jetpack Compose, for working on real code projects from your phone. The agent reads and edits files, runs commands in a real Linux toolchain (bash, git, python, node), pushes to GitHub, drives a browser, and shows every step in a chat you can inspect.

Choose the mode that matches the job:

- **Code** is the agent. It works inside a project folder with files, shell, git, tests and browser tools.
- **Chat** is for everyday questions, research, writing and brainstorming. It uses read-only tools and can hand work over to Code when a conversation turns into a project.
- **Phone** is the assistant you can summon over another app. It resumes your conversation, can open a named app directly, and can plan and carry out a multi-step task on the phone. Turn on hands-free for one task or keep individual approvals. [Phone assistant guide](docs/phone-assistant.md)

You can start for free with the built-in provider, which needs no account, or run models fully offline on the device.

## Screenshots

<table>
  <tr>
    <td align="center" width="20%"><img src=".github/assets/showcase/01-code.jpg" alt="Code agent building an app" width="100%"><br><sub>Code agent at work</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/02-chat.jpg" alt="Chat answering a question with a rich card" width="100%"><br><sub>Chat with rich answers</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/03-providers.jpg" alt="Provider picker" width="100%"><br><sub>Switch providers</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/04-code-installation.jpg" alt="Approval card for installing Linux packages" width="100%"><br><sub>Approve before installs</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/05-code-options.jpg" alt="Code chat options menu" width="100%"><br><sub>Chat tools and modes</sub></td>
  </tr>
  <tr>
    <td align="center" width="20%"><img src=".github/assets/showcase/06-settings.jpg" alt="Settings screen" width="100%"><br><sub>Settings</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/07-code-settings.jpg" alt="Coding settings" width="100%"><br><sub>Coding settings</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/08-plugins.jpg" alt="Plugin manager" width="100%"><br><sub>Plugins and skills</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/09-9router.jpg" alt="9Router local router page" width="100%"><br><sub>9Router on the phone</sub></td>
    <td align="center" width="20%"><img src=".github/assets/showcase/10-local-models.jpg" alt="Local models page" width="100%"><br><sub>Offline local models</sub></td>
  </tr>
</table>

## Highlights

- **A real coding agent.** File edits, shell, git, tests, browser control and subagents. Every tool call gets a card you can open to see exactly what happened.
- **An assistant everywhere.** Make AndroidHarness your phone's digital assistant and ask about any screen without leaving the app you are in. Models that cannot see pictures can read the text inside them on the phone.
- **Goals that finish the job.** `/goal` keeps the agent working turn after turn until a separate check agrees the task is done, with parts, a budget and a Resume that keeps its progress.
- **A real shell.** Commands run through Shizuku, Wireless ADB pairing, optional root, a Termux-based Linux toolchain, or a toybox fallback. A shell policy and a secret redactor sit on top.
- **Free to start.** The built-in keyless provider works without sign-up, and local GGUF models run offline. Cloud API keys and ChatGPT accounts are optional.
- **Free speech.** Edge speech is built in with no key, more than 300 Microsoft voices and a sample for each. Groq, OpenAI, ElevenLabs, Deepgram, Cartesia and AssemblyAI can listen, speak or both.
- **Many providers.** Anthropic, Google Gemini, ChatGPT accounts, any OpenAI-compatible endpoint, and fallback chains that switch models when one fails.
- **Research that reads.** Turn on any of eleven search providers; the agent picks one, falls through failures, reads the top pages and returns passages. Shaky statements can be flagged, and a question can be re-run deeper.
- **Plugins, skills and MCP.** Install plugins from npm, GitHub, Claude marketplaces, archives or a folder on the phone, and the agent can manage them when you ask. Plugin commands, binaries and language servers run like Claude Code, and MCP servers connect over stdio or HTTP.
- **Built for long runs.** A foreground service, resume cards, a saved message queue and optional auto-continue keep work safe when the screen turns off or the connection drops.
- **Phone approvals.** Review a proposed phone task before it starts. Hands-free approval lets the assistant carry out that task, including sending or deleting, without asking about each action. Commands and scripts still need approval.
- **22 interface languages.** Switch the app language at any time without a restart.

## From upstream 4.1

- **Your assistant over any app.** Set AndroidHarness as your digital assistant and ask about what is on screen, by voice or typing, without leaving the app you are in. Models that cannot see images can now read the text inside pictures on the phone.
- **Goals that keep going.** `/goal` turns a task into a loop that keeps taking turns until a separate check agrees it is done, with a turn limit, an optional check command, a budget, named parts, a per-turn change summary and a Resume that keeps its progress. Keep improving can push past done.
- **Backups and setup rebuilt.** Backup & restore streams one file, lets you pick chats, and now carries images and usage stats. Setup is a page-by-page flow that can restore chats and settings, and file access is optional.
- **Memory you can shape.** Folders with colors, trigger words, trigger-only memories, multi-select move and delete, and a zoomable map of how memories connect.
- **Exa joins search.** Exa searches by meaning instead of keywords and can return full page text, alongside the ten existing providers.
- **A calmer chat.** A floating task pill, a slash menu docked on the composer that follows the cursor, editing a sent message's photos and files, background browser screenshots, and per-item trust for workspace tools.

See [CHANGELOG.md](CHANGELOG.md) for the upstream release notes.

## Getting started

1. [Build from source](#build-from-source), or install a fork APK when one is available on the [fork releases page](https://github.com/CreatorAA/AndroidHarness/releases). To update, install a newer APK signed with the same key.
2. Grant file access if you want it. All files access lets the shell and file tools use real paths; without it the app works in its own folder and asks when a task needs your files.
3. Pick a model. Use the built-in keyless provider, add an API key in Settings, or download a model in Settings → Local models.
4. Recommended: install [Shizuku](https://github.com/RikkaApps/Shizuku), pair Wireless ADB in Settings → Terminal & device, or install the Linux environment from inside the app. This lets shell commands run with proper permissions. Rooted devices can also turn on direct root there.

Then open a workspace and ask for a change, or open Chat for anything that does not need a project. If something feels off, run `/doctor` to test every tool family.

## Features

A short tour of what the app can do. The complete list, with every detail, is in [FEATURES.md](FEATURES.md).

**Code**
- Streaming chat with thinking blocks, tool cards, diffs and verification cards that list every build or test that ran.
- A workspace is one folder, and a project groups workspaces for chats that need several, each editable or read only.
- Plan mode, dual planning (plan on one model, execute on another), subagents and a persistent message queue that can hold send to choose when a queued message is read.
- Goals that keep working across turns until a separate check agrees the task is done, with parts, a turn limit, a check command, a budget and Keep improving.
- Approve privileged device commands from the chat, set alarms and timers, or schedule a message.
- Scheduled automations that run prompts daily, hourly or on demand.

**Chat**
- Web research across eleven providers, with the top pages read for passages, cited sources, claim checks and adjustable depth.
- Reminders, alarms and timers, photo paste, a single attachment tray and a full-screen gallery.
- Personal Chat agents with their own instructions, model, tools and knowledge files.
- Rich answers with tables, charts, diagrams, images and interactive previews.
- Shared memory with Code, saved answers and voice input and output.

**Assistant**
- Use the assistant gesture over an app, then ask about the screen or request an action. The model can use text and images Android shares for the task.
- Read text in images on the phone for models that cannot see pictures.

**Files, editor and Git**
- File manager, tabbed code editor with syntax highlighting, search and replace, and offline language tools.
- Side-by-side diff viewer, per-chat change tracking with undo, and a Build & Test dashboard.
- GitHub sign-in, repository import, commit and push, and pull requests from the app.
- SSH workspaces and FTP or SFTP sync.

**Models**
- Built-in free provider, ChatGPT accounts, Anthropic, Gemini and any OpenAI-compatible API.
- Up to 20 rotating API keys per provider, proxies, custom headers, key testing and fallback chains.
- Voice providers for listening and speaking, including free Edge speech with more than 300 voices.
- On-device GGUF models with optional GPU acceleration, plus 9Router as a local model router.

**Personalization**
- Custom color palettes, a background canvas, experimental glass effects and haptic feedback.
- Expressive settings in named sections, custom settings sections you build yourself, and motion blur on long lists.
- Phone and desktop layouts with keyboard shortcuts.

## Shell access

The command target, workspace and access settings determine where a command runs.

| Command target | Runs as | Environment |
| --- | --- | --- |
| Privileged commands | shell uid via Shizuku | system access, like adb shell |
| Privileged commands, no Shizuku | shell uid (2000) via Wireless ADB | system access, like adb shell |
| Explicit root requests | UID 0 via the verified root service | full device access, when root is enabled |
| Workspace commands | app uid | Linux toolchain with bash, git, python and node |
| Fallback | app uid | toybox sh when nothing else is installed |

Root is always optional and opt-in. More detail is in [FEATURES.md](FEATURES.md#terminal-and-shells).

## FAQ

**Do I need a PC?**
No. Editing, shell, git, GitHub, SSH, the models, and even building and signing APKs all happen on the phone.

**Do I need an API key?**
No. The built-in provider offers free models without an account, and local models run offline. Keys are only needed for cloud providers.

**Do I need root?**
No. Shizuku or the built-in Wireless ADB pairing gives shell-level access without unlocking the bootloader. Root is supported if you have it.

**Does it work offline?**
Local models do, once downloaded. Web search, page reading and GitHub need a connection.

**Which languages are supported?**
English, Simplified Chinese, Traditional Chinese, Russian, Arabic, Persian, Brazilian Portuguese, German, Spanish, Japanese, Indonesian, Sinhala, Vietnamese, Turkish, Malay, Thai, French, Hindi, Korean, Tamil, Bengali and Filipino.

**Where do my chats and keys go?**
Your selected model receives the conversation. Tools such as web search can also send data to the services you enable. Provider credentials are stored encrypted on the device; avoid pasting them into chats or files you share. Settings backups include API keys only if you ask, and ChatGPT sign-ins are never included.

**Can it damage my files?**
Yes. You can review and undo edits recorded by the file tools. Shell commands, plugins and phone actions can change data outside that history. Review their permissions and keep a backup of work you need.

**Which devices are supported?**
Android 8.0 and newer. Local models need a compatible 64-bit device.

## Build from source

The build workflows use Temurin JDK 21. For a full native build, install Android SDK Platform 37, NDK 27.2.12479018 and CMake 3.22.1.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Local models run on CPU without a separate runtime. For a build that can download the optional Vulkan backend, use the [matching GPU packages](repowiki/74-build-ci-and-testing.md#building-with-the-published-gpu-runtime).

When enabled, the [debug workflow](https://github.com/CreatorAA/AndroidHarness/actions/workflows/nightly.yml) builds development APKs on changes to the app on `main`, nightly, or on demand. Its artifacts are not published releases. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full setup.

## GitHub setup

In Settings → GitHub, choose **Sign in with GitHub** or **Use a personal access token**.

- Device sign-in shows a code that you enter on GitHub in your browser.
- Only enable workflow access if you need to edit GitHub Actions files.
- Fine-grained tokens need the target repositories and **Contents: read and write**. Editing workflows also needs **Workflows** access.
- Organizations may require approval or SSO authorization.

Importing a repository needs the Linux environment. Clones go into private app storage, so broad storage permission is not required. Pushes never force-push, and each pushed commit is checked against the remote branch.

If a push automation fails, open its history to see the real cause. Common ones are the wrong workspace, missing Git, an SSH remote, missing token permissions, newer commits on the remote, or branch protection. A failed push can be retried with **Push existing commits**.

<details>
<summary>Building with your own OAuth app</summary>

Register a [GitHub OAuth app](https://github.com/settings/developers), turn on **Enable Device Flow**, and set `GITHUB_CLIENT_ID` in the ignored `local.properties` file. Local debug and release builds include that value. The Client ID is not stored in the repository source, docs or public workflows, and no client secret or auth server is used. Builds without it still support token login. Keep a token as a backup, since GitHub rate limits Device Flow for shared OAuth apps.

</details>

## Documentation

- [FEATURES.md](FEATURES.md): the complete feature reference
- [Configuration guide](docs/configuration/README.md) and [JSON Schema](docs/harness.schema.json) for `harness.json`
- [Plugin guide](docs/configuration/plugin-manager.md)
- [Developer repo wiki](repowiki/README.md): architecture of the agent engine, tools, providers, MCP and workspaces
- [CHANGELOG.md](CHANGELOG.md), [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md)

## Contributing

Bug reports, fixes, tests and documentation are all welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md) and use the issue forms for bugs and feature requests. Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). Translations are handled on [POEditor](https://poeditor.com/join/project/ZPbHNa9WWc).

## Acknowledgements

Sanuu7 created [the original AndroidHarness](https://github.com/Sanuu7/AndroidHarness). The [upstream Discord](https://discord.gg/NdC5K3XeWF) and [donation page](https://buymeacoffee.com/sanuj7) are run by the original project.

Offline Mandarin dictation uses Alibaba's Paraformer model, quantized by crazyant and packaged for sherpa-onnx by csukuangfj. [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runs it with [ONNX Runtime](https://github.com/microsoft/onnxruntime); [Silero VAD](https://github.com/snakers4/silero-vad) detects speech. The [bundled notices](app/src/main/assets/licenses/offline-speech.txt) link to the model and conversions. Phone control can optionally use [AutoJs6](https://github.com/SuperMonster003/AutoJs6) and its [MCP Server plugin](https://github.com/SuperMonster003/AutoJs6-Plugin-MCP-Server).

AndroidHarness also draws on these projects:

[Hermes Agent](https://github.com/NousResearch/hermes-agent),
[Aider](https://github.com/Aider-AI/aider),
[pi (ohmypi)](https://github.com/earendil-works/pi),
[OpenCode](https://github.com/anomalyco/opencode),
[Claude Code](https://github.com/anthropics),
[Roo Code](https://github.com/RooCodeInc/Roo-Code),
[Cline](https://github.com/cline/cline),
[browser-use](https://github.com/browser-use/browser-use),
[Termux](https://github.com/termux),
[Shizuku](https://github.com/RikkaApps/Shizuku),
[CodeGraph](https://github.com/colbymchenry/codegraph),
[Caveman](https://github.com/JuliusBrussee/caveman),
[llama.cpp](https://github.com/ggerganov/llama.cpp),
[sora-editor](https://github.com/Rosemoe/sora-editor) and
[Eruda](https://github.com/liriliri/eruda).

## License

Released under the [MIT License](LICENSE).
