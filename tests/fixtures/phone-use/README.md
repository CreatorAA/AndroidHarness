# Phone Use fixture

Install this independent, permission-free Android app on a test emulator to check whether the assistant actually typed the requested text, chose the intended one of two equally named buttons, and submitted only once. The counter belongs to this fixture and survives restarts. No account or network is involved.

On Windows, run `build.ps1` with `ANDROID_HOME` and `JAVA_HOME` set, or pass `-Sdk` and `-Jdk`. The fixture uses Android platform 34 and Build Tools 36.0.0, independently of the main Gradle build.

Install `build/phone-use-fixture.apk` on the test emulator and launch `com.androidharness.fixture.phoneuse/.FixtureActivity`. On the first page, enter e.g. `你好 Android 🐦`, choose the lower **选择** (白鹭), verify the selection and text on the second page, then submit once. The final receipt must contain the same text and selection, and the previous count plus one.

To test the full task, install matching Harness debug and androidTest APKs on the emulator. Enable the built-in service in Android accessibility settings, or pair the AutoJs6 companion in Harness. Run `test-on-emulator.ps1 -Serial emulator-5564 -Backend builtin` (or `autojs`), substituting your emulator serial.

The direct-driver test checks multiline text, emoji and quotes, duplicate buttons, a real screenshot, and rejection of a replayed submission. The second test uses the real agent engine and a local simulated provider; it selects controls from four fresh observations, checks the screenshot for the first page’s teal header, grants one submit approval, and verifies the final receipt. The instrumentation output includes the starting and final counts. No account or model key is needed.

For the separate system-overlay test, select Harness as the emulator's default digital assistant, turn on **Use screen and app data** on that system screen, and enable the built-in accessibility service. With the same debug and test APKs installed, run:

```powershell
adb -s emulator-5564 shell am instrument -w -e assistantPhoneOverlay true -e class com.androidharness.app.assistant.AssistantPhoneOverlayDeviceTest com.androidharness.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

This test uses the Android assistant key to open the real panel above the fixture. It checks the named app in the consent dialog, the task result after the panel returns, and cancellation when the user summons the assistant again.

### Test actual Direct Actions, not intent-filter matches

On a device with the current Harness elected as the digital assistant and screen context enabled, install the fixture. Open a non-sensitive page of the app you want to check. Replace `SERIAL` and `PACKAGE` below with its device serial and package:

```
adb -s SERIAL shell am instrument -w -e target PACKAGE com.androidharness.fixture.phoneuse/.ReadPageProbe
```

The probe opens **Operate phone → Check page actions**, verifies that the assistant's inline task prompt names the requested package, and reports the result shown by Harness. It never approves an action or sends a model request. A timeout, a package mismatch, or an unavailable page is not a zero-action result. On a debug installation, add `-e harness com.androidharness.app.debug`.

Positive control: pass `-e target com.androidharness.fixture.phoneuse -e startFixture true`. The dedicated fixture page should report one action, `show_test_receipt`. Add `-e verifyCancel true` to verify that opening the task and cancelling return to the ordinary input in the same assistant window. An optional `-e captureFixture true` stores a screenshot of the fixture and Harness result in the fixture's external files directory. The original message/selection fixture remains a screen-only app unless the separate test explicitly enables its temporary map handler.

On the disposable assistant emulator, with matching debug and test APKs and the built-in service enabled, run the routing test:

```
adb -s emulator-5564 shell am instrument -w -e nativeRouteFixture true -e class com.androidharness.app.assistant.DirectActionBridgeDeviceTest com.androidharness.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Its local simulated model verifies four routes on the same activity: perform the public map search and check the exact Unicode query, decline it and perform the page action, decline both and read screen text, or explicitly request `route="screen"` on the first observation. The last case must return the real nodes immediately, even though both other routes are available. The test disables the map handler at the end.

**Explicit real-model check:** Unlike the read-only probe, `LivePageProbe` uses the existing model and may incur a model charge. Only after the device owner authorizes a real model turn, run:

```
adb -s SERIAL shell am instrument -w -e target com.androidharness.fixture.phoneuse -e live one-authorized-real-model-turn com.androidharness.fixture.phoneuse/.LivePageProbe
```

The runner uses a freshly opened fixture page, submits one synthetic task in the ordinary input, confirms the known read-only tools and the fixture's single matching page action, and compares the assistant’s answer with the independent receipt. It does not change providers or read credentials. Unknown requests are not approved.

### Map search from an ordinary assistant question

`MapChatProbe` sends through the normal text input, **not** “Operate phone”. Every case uses the configured model and may incur a charge. Use automatic management for `amap-direct`; turn it off for proposal/cancellation cases. Its `mapCase` selects one action:

- `baidu-missing`: from Home, request 百度地图 → 纸月测试公园, verify the missing-app explanation and named 高德 alternative, then cancel. Use only on a device with Baidu absent and AMap installed.
- `natural-preview`: use the configured model over an inert fixture page, ask “纸月测试公园这个地方，我想直接在高德里看一下”, and cancel the resulting map proposal. This may incur a model charge.
- `amap-direct`: from Home, send an explicit request naming 高德地图 and 纸月测试公园, then make **no additional taps**. Verify the actual AMap foreground page contains the requested query and matching controls. This opens the real map app; it never selects a result, starts navigation, logs in or grants location. The test records only match counts, not map screenshots or unrelated place names.
- `fixture-map`: on a test emulator only, offer the fixture’s `geo:` handler; confirm it in ordinary chat and verify `Public map receipt: 纸月测试公园` in the fixture. Disable its optional map alias after the test.

With the device owner’s permission, use:

```
adb -s SERIAL shell am instrument -w -e live one-explicit-device-trial -e mapCase MODE com.androidharness.fixture.phoneuse/.MapChatProbe
```

For an emulator running Harness debug, also pass `-e harness com.androidharness.app.debug`.

### On-device checks: voice recovery, managed launcher journey and conversation continuity

Use the release assistant and its existing model. These scenarios require the phone owner's permission. Install the independently built fixture; do not start Calculator or trigger its keys with ADB. The instrument only controls the assistant window. Do not run if the user is interacting or has unfinished calculator work.

```
adb -s SERIAL shell am instrument -w -e live one-explicit-device-trial -e target com.androidharness.fixture.phoneuse -e scenario journey com.androidharness.fixture.phoneuse/.CalculatorJourneyProbe
```

- `voice`: on an inert fixture page, wait for the real recognizer's recovery UI. Enter an unsent test draft, select **继续听写**, then return to the editor. No audio is synthesized or exported; hearing a person's speech is a separate test.
- `journey`: submit “回到桌面找到计算器，点开它，然后在计算器里算 37×19” through the normal assistant input. Select **本次托管并开始** once. Observe the actual system launcher and calculator windows, look for the known expression and result on Calculator, and independently match the result in the returned assistant answer. Do not grant any subsequent approval or use an alternate app. This makes real model requests and may incur charges.
- `history`: only after a successfully completed synthetic journey, dismiss and invoke the assistant twice, check for the known transcript marker each time, then ask **重开新会话** and verify the old marker is gone. This checks the model's new-conversation tool rather than the literal **/new** shortcut. Earlier chats are not deleted.
- `clarify-calculator`: start a fresh chat from the fixture and ask the real assistant to open Calculator, confirm the second number, then enter the multiplication there. Answer **对** once in the ordinary composer, never approve a second task. The probe checks the real equals-key event and the two specific Calculator fields for **9×19 = 171**; it does not read calculation history or press Calculator keys.
- `calculator-once`: with persistent auto-management **off** and MIUI Home in front, send the original **开计算器计算20 × 18** and tap **本次托管并开始** once in the ordinary assistant. The probe refuses any additional approval, detects calculator key presses, checks both target fields (**20×18**, **= 360**) and waits for the terminal task indicator. It does not change settings or examine launcher icons.
- `reconnect-setup`: only when the system role still names Harness but the voice-interaction service is disconnected. Open the app normally, tap its **重新连接** action, and check that MIUI opens the system assistant chooser. This scenario never changes the user's selected assistant.

The probe does not enumerate launcher icons, take personal screenshots, or export other calculator values.

On a release with the task progress island, `-e scenario island` uses the same harmless fixture: a natural request enters **海盐** and selects the lower **白鹭** option, stopping at review. The probe does not submit. It checks the resulting fixture fields and the live island headlines and writes narrowly cropped running/terminal images to the fixture's external files directory. A numbered two-step headline or a visible sequence tool is evidence that the real model selected batching; the target receipt alone does not establish batching.

`-e scenario offline-mic` starts the normal assistant's microphone with an unsent local draft, observes only the release app's own recording AppOp for at least six seconds, then returns to typing. Pair it with process-scoped Vosk/AudioRecord startup metadata when verifying a bundled decoder; no ambient audio or transcript needs to be collected.

### Real-model AutoJs6 script round trip

Install the fixture from `build.ps1` on an unlocked, idle phone with a paired AutoJs6 plugin, the release assistant, and a configured model. Leave automatic management at the owner's setting. With the owner's consent, run:

```powershell
.\test-autojs-journey.ps1 -Serial $serial -Sdk $env:ANDROID_HOME -EvidencePath "$env:TEMP\autojs-trial-1.txt"
```

The probe submits one ordinary phone-assistant request: create and check a uniquely named AutoJs6 script, run it to fill “蓝桥27” and choose the upper “青竹”, then run an anonymous script to go back, replace the text with “海盐42” and select the lower “白鹭”. With automatic management off it grants “本次托管并开始” once; with it on, it records zero start approvals. If a separate local save approval appears, it allows that single save only when the full arguments match the unique script title and the expected form operations. It never fills or presses any target control. The target's rendered views independently attest to both exact reviews and the intervening edit page; the local submission count must not change.

MCP tool counts in the evidence file refer only to the plugin process and this client's grant interval. They establish the driver used; to establish **script** execution, inspect this turn's actual `autojs_run` tool receipts under the assistant activity details (input `name` vs `source`, output `status: ran`). A static `validated_no_execution` preview, a result claimed by the model, or ordinary MCP clicks alone does not establish that a script ran.

### Verify that a page-analysis answer actually inspected the reference

From the home screen or fixture, run the following on an unlocked, idle test device. It uses the ordinary quick assistant with its existing conversation and model:

```
adb -s SERIAL shell am instrument -w -e live one-explicit-device-trial -e target com.androidharness.fixture.phoneuse com.androidharness.fixture.phoneuse/.ReferencedChatTrial
```

The source page deliberately omits the explanation. Only opening its quoted record reveals the per-run model number and original-font-library detail. The probe never opens it for the assistant. A pass requires a real quote-open event, a rendered detail page, both details in the reply from this turn and zero sent messages. The entire conversation and all test names on this screen are synthetic.
