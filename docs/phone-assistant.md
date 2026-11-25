# Using the phone assistant

**English** | [简体中文](phone-assistant-zh.md)

Summon the assistant from the home screen and try **“Open a map app. Don’t pick a destination.”** Opening the app is the whole task: there’s no reason to search for a place or start navigation. If you ask for a particular map app and it isn’t installed, you can choose an alternative or cancel.

The next time you summon the assistant, it picks up the latest Phone conversation in the current project. Choose **Continue in app** to open the same conversation in AndroidHarness, or **New question** to start another. You can also ask to start a fresh conversation.

The app keeps the ten most recently opened or updated Phone conversations across all projects. Older conversations are removed once they’re no longer running. This limit doesn’t affect Chat or Code. A long Phone conversation can continue from a summary when it reaches the context limit. Reopening its history never restores permission to operate the phone.

## Set it up

1. In AndroidHarness, open **Settings → Assistant over other apps**. Choose a model that can call tools. If you want it to inspect pictures rather than just read their text, choose a model that accepts images.
2. Select **Choose assistant app** and make AndroidHarness your default digital assistant. In Android’s assistant settings, enable **Use text from screen** so the assistant can identify the app you mean. Enable screenshot access if you want to share images. Screen content used for a task may be sent to your chosen model.
3. Public app commands and page actions work without Accessibility. For taps, scrolling and typing on pages without those interfaces, go to **Operate phone → Open accessibility settings** and enable **Harness phone controls**.
4. Enable **Run in background** when prompted so the assistant can get its panel out of the way and keep working. Open the app you want help with, then summon the assistant there. Ordinary chat doesn’t need phone-control permissions.

Use the phone’s usual assistant gesture, or enable the optional **Edge handle**. Swipe inward on the handle to summon the assistant; drag it up or down to reposition it. It moves out of the way during a phone task. If Android asks you to unlock the phone or confirm your identity, do that yourself in the system dialog.

## Ask for a result, then choose what to approve

“Where can I find Help?” asks for an answer. **“Open this app’s Help page and tell me its first heading”** asks for an action and a way to check the result. You don’t have to supply a route through the menus. Opening the app is only the first step; the heading on the destination page tells you the assistant got there.

By default you see a task card before phone control begins:

- **Allow and start** starts the task under your usual tool approval settings.
- **Run hands-free for this task** lets the assistant carry out this task without stopping at each ordinary action. That includes switching apps, typing, sending and deleting when your request calls for it. Commands and scripts still need approval.
- To use the second behavior for future phone requests, turn on **Settings → Assistant over other apps → Operate phone → Automatically run phone tasks**. This changes when the assistant asks, not how you have to word a request or what counts as completing it.

Be specific about the boundary that matters to you. **“Explain the Help section visible here; don’t scroll”** differs from asking the assistant to read the entire section. In the latter case it can follow a reference, open an illustration or scroll for evidence. By default, “this” means the page you invoked it over; say so if you mean an earlier image. Where several entries have the same name, it can use the rest of the row to distinguish them. Opening an entry doesn’t require tapping the separate button beside it.

The same principle applies to questions without a phone action. If you ask for the current time, it can use the device time at the moment you ask. You can ask for digits instead of a clock face, or for a table rather than a chart when comparing values. Tapping a chart lets you inspect a reading. To find out whether a *phone action* succeeded, look at the task result rather than the illustration.

If you ask the assistant to use a home-screen icon, it checks which app actually opened before continuing. It can group several actions visible on one page, checking each control again before using it. On a new page it inspects the controls afresh. If interrupted, it records which steps ran instead of repeating them all.

Answer a clarification in the same conversation; you don’t need to start over or grant access again. A task can switch apps and return to the original page. Locking the phone, cancelling, or leaving a clarification unanswered for five minutes ends that control session.

**Closing the panel is different from stopping the task.** With background runs enabled, you can press Back or close the panel after sending the request. Summon the assistant again to check progress or pause phone controls. Pending approvals still wait for you. Without background runs, continue from the original conversation in the app. To end the task, use **Stop task** in the indicator at the top of the screen or in the running notification. The notification is also available without the built-in accessibility service. A finished task leaves you on the destination page; its result distinguishes verified completion from a stop that needs your help.

## How the assistant operates an app

- **Public app commands** ask an app to do something it explicitly supports. An instruction to open an editor normally leaves it open for input; an instruction to set a timer can create the timer immediately.
- **Page actions** are Android Direct Actions that an app offers on its *current page* (Android 10 and later). Tap **Check page actions** to list them locally without starting a task. An empty list means that page advertises none; the same app can still support a public command elsewhere.
- **Screen controls and images** are useful when the page itself holds the answer. The assistant can read controls, scroll, open images or read their text on the device. It can tap by image coordinates only when its model has actually received the current screenshot. Android’s screenshot protections still apply.

## Start a task from Code

In a local Code conversation, send `/phone Open this app’s Help page and read the first heading`. Within ten minutes, open the target app, summon the assistant and choose **Operate phone**. Review or edit the draft. Sending it from Code doesn’t start phone control; confirming the draft in the assistant does.

Back in the same Code conversation, use `/phone --result` to open the most recently confirmed Phone conversation and see what happened. Use `/phone --cancel` to withdraw an unconfirmed draft. A new draft replaces the previous one. Sending just `/phone` opens assistant settings.

## Add AutoJs6 for scripts or extra controls

The built-in service works on its own. You can add AutoJs6 alongside it if you want to run reusable UI scripts or use the plugin’s controls.

1. Install [AutoJs6 6.8.0](https://github.com/SuperMonster003/AutoJs6/releases/tag/v6.8.0) and its [MCP Server 1.3.1 plugin](https://github.com/SuperMonster003/AutoJs6-Plugin-MCP-Server/releases/tag/v1.3.1). Start the plugin in AutoJs6, allow pairing notifications and copy the token it displays.
2. In **Settings → Assistant over other apps → Operate phone**, enable AutoJs6 and open **Configure AutoJs6**. Enter the port and token shown by the plugin. The token is stored encrypted on the phone.
3. Tap **Pair / check connection** and confirm the request in the plugin’s notification. Once paired, return to the target app before summoning the assistant.

**Permissions belong to the app that uses them.** Enable AutoJs6’s accessibility service for finding controls, tapping, swiping and entering text. Allow the MCP plugin to show its pairing and status notifications. The microphone for dictation belongs to AndroidHarness, not AutoJs6. AndroidHarness only needs notification access if you set up notification-based reminders. Basic AutoJs6 control doesn’t need overlay access, all-files access, root, Shizuku or a LAN connection.

Tool availability is separate from Android permissions. The built-in service supports swipes and press–move–release drags. For coordinate swipes through AutoJs6, enable the plugin’s `ui_gesture` tool group (off by default in 1.3.1). A driver that only offers a swipe isn’t treated as supporting a drag. Screen capture normally uses Accessibility; if it falls back to Android screen recording, confirm sharing for the task when asked. When switching drivers, the assistant checks the connection and observes the page again; it doesn’t repeat an action just because the previous driver couldn’t confirm its outcome.

If switching apps disconnects the plugin, open the **MCP Server** app’s battery settings. Where available, allow unrestricted background use, then test the connection again. If it disconnects only after sitting idle, also check whether the plugin is set to stop when idle. After a reboot, open AutoJs6 once. If you manually stopped the server, turn **MCP Server** back on from the AutoJs6 sidebar. You don’t need to pair again just for a restart.

### Check a script before it touches the phone

Open **AutoJs6 scripts** to save and enable a reusable script, or leave the name empty to try one without saving. **Check steps** validates the instructions locally without touching the screen. **Try in target app** prepares a run for the app you authorize. For example, on a sample page with a **Help** button that opens a **Help topics** heading:

```js
text("Help").findOne(1500).click();
text("Help topics").waitFor();
```

Use labels or IDs actually present on the target page. Scripts accept one instruction per line and can match exact visible text (`text`), accessibility descriptions (`desc`) and resource IDs (`id`). They don’t run general JavaScript, shell commands, file operations or network requests.

Ask to save a routine if you’ll use it again, or ask to run it just once. A saved script or a successful syntax check is not an execution result. In the example, the second line checks the destination *after* the tap; a check at the beginning would only tell you the starting page was ready. If you disable AutoJs6 later, your saved scripts remain, but the model no longer gets its tools or usage instructions.

## Dictate in Mandarin without racing the silence timer

In voice settings, choose the **Inbuilt** speech-to-text engine and turn off **Auto send** to leave time for pauses and edits. By default Android’s speech service listens first. If it stops or becomes unavailable, the bundled Paraformer model can continue on the phone without losing words already in your draft. Turn on **Offline Mandarin only** to use the local model from the first word; no key or model download is needed. If a selected cloud recognizer fails, the app can instead transcribe the same recording locally.

Read the draft before sending. Spoken numbers can turn into quantities, digit sequences with leading zeros or arithmetic expressions; correct the text if the distinction matters. Offline dictation only covers turning audio into text. A cloud chat model still needs a connection to answer. If dictation stops, you can edit the words already shown and carry on by voice or keyboard.

## Get a reminder instead of running a task

Open **Phone event reminders**, choose an app, then choose whether to be reminded when it opens or when it posts a notification. Notification-based rules need Android’s notification access. A match creates a reminder; even if you linked a task, you still decide whether to run it. Turning a rule off doesn’t change ordinary chat.

## Troubleshooting

- **The edge handle disappeared.** Open AndroidHarness. If it says the system disconnected the assistant, tap **Reconnect**. In Android’s digital assistant settings, choose **None**, then choose AndroidHarness again. You can also get there from **Settings → Assistant over other apps**. There’s no need to clear app data or reconfigure a model. The edge handle that opens the panel is separate from the top indicator for a running task.
- **Android didn’t identify the app.** Enable **Use text from screen** in the system’s digital assistant settings. Return to the app you want to use, then summon the assistant again.
- **The page changed or a control is missing.** Leave the target page visible and let the assistant inspect it again. Handle a lock screen, permission dialog, split-screen conflict or sensitive input yourself before continuing.
- **A tap ran but its effect isn’t clear.** The next step is to inspect the screen again. A drag or a series of taps need not change pages or reveal text. Sending or paying is different: if the assistant can’t tell whether a submission took effect, it won’t automatically submit it again.
- **The model doesn’t accept images.** It can still use page controls. If a screenshot is available, on-device recognition can read Chinese or English text. If an action needs a pixel location and the model hasn’t received the image, it won’t guess where to tap.
