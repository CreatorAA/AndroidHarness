# Privacy Policy for AndroidHarness

Last updated: November 25, 2026

AndroidHarness runs on your device. It is a tool that connects to services you
choose and configure yourself. I built it so that your data stays between you
and those services. This page explains what the app handles and what it does
with it.

## What the app stores on your device

Everything the app needs to work is stored locally: your chats and agent
history, files you open or edit through the file manager, your settings, and
the credentials you enter. Credentials such as API keys, GitHub tokens, and
sign-in tokens for MCP servers are kept in encrypted storage that uses the
Android Keystore. Chats and files remain local unless you use a feature that sends them to a service as described below.

When you attach an image in Code, the app also tries to save the original under
`.harness/attachments/` in the selected workspace so shell tools can read it.
For an SSH workspace this writes the file to that host; for a shared folder the
copy is visible to others with access to the folder. Chat and Phone do not make
this workspace copy.

## What the app sends, and where

When you use the agent, the app sends your prompts and the context needed to answer
them (which can include file contents and command output from your device) to
the AI provider you configured, for example OpenAI, Anthropic, or any other
OpenAI-compatible endpoint you entered. Web search queries go to the search
service you configured, or to public search engines when no API key is set.
If you connect a GitHub account, you create a personal access token on
GitHub and paste it into the app. The app checks the token by calling
GitHub's API with it and saves it only after GitHub confirms it belongs to
you. The token is sent only to GitHub: for the check, for repository
requests, and for Git operations. There is no separate login server between
you and GitHub. If you add an MCP server, the app talks to that server directly, and
any OAuth sign-in happens between you and that server.

Which services receive data, and what keys they use, is entirely up to you.
If you remove or change a provider, server, or key in settings, the app stops
using it.

## When you dictate

Phone audio normally uses your Android system speech service, which may use
the network. For Mandarin, turn on **Offline Mandarin only** to transcribe
on the device from the first word. If the system service becomes unavailable,
AndroidHarness keeps its draft and can recognize new speech locally. If you
choose a cloud transcription provider, that provider receives the recording;
a failed cloud request can use the same recording for local recovery. Dictating
offline does not send audio to your chat model. Sending the text to chat is a
separate action; auto send performs it when the turn is ready.

## When you ask the assistant to use your phone

Your selected model receives your request even for a simple action such as opening a map. If it proposes searching a named map, AndroidHarness checks locally whether that app accepts a place search. With automatic approval off, you choose whether to proceed. A missing map is not silently replaced with another.

Choosing **Operate phone** shows you which app the assistant will operate. During
that task, public command names, page action IDs and results, visible text, the names of controls and screen images can become
part of the conversation sent to your selected model. Saved screen images
use the same local storage as other chat images. Optional text recognition
runs on the device. **Check page actions** only asks the foreground page for its actions and shows them locally; it does not start a model request or execute an action. With background runs enabled, closing the panel leaves the current task and its existing permissions in place. Use **Stop** in the task indicator or notification to end further observations and actions. Closing the panel does not approve a pending request.

The built-in driver uses Android's accessibility service, which you enable
and can revoke in system settings. The optional AutoJs6 driver connects to
the companion on the same phone; its pairing token is stored in the Android
Keystore-backed credential store. The companion manages its own permissions.

The quick assistant remembers its last Phone conversation per project. AndroidHarness keeps the ten most recently used Phone conversations across projects and automatically deletes older ones, including their messages, once inactive. This limit does not apply to regular Chat or Code conversations, or to separately saved memories.

If you enable **Automatically run phone tasks** on this device, your next phone task can start without another confirmation. During a hands-free task, the assistant can change apps and perform actions such as sending or deleting without AndroidHarness asking again. Running a command or script still needs approval. This setting is off by default; chat history alone does not authorize a new task. Launcher traversal looks for the named icon locally without sending desktop widgets or images to the model. Editing or checking an AutoJs6 script does not operate the phone.

Phone event reminders are separate and off by default. If you enable them,
you choose the apps and events to watch. A matching event can create a local
reminder; it does not grant an agent permission to read or operate the phone.
The reminder does not copy the originating notification's message body.

## What I collect

The app has no analytics, advertising, crash reporting, or telemetry.

## Backups

App data may be included in backups handled by your device's backup system
and your Google account settings. You can turn that off in your device's
backup settings.

## Children

The app is a developer and power-user tool and is not directed at children
under 13.

## Changes

Changes to this fork’s data handling will be documented here.

## Contact

Questions or concerns: open an issue at
https://github.com/CreatorAA/AndroidHarness/issues
