# App Version and Manual Upgrades

Open **Settings → About** to see the installed version. Choose **GitHub Releases** to open [the fork’s release page](https://github.com/CreatorAA/AndroidHarness/releases). When a newer custom APK signed with the same key is available, install it using the Android package installer. You do not need root or Shizuku. If Android reports a signing conflict, keep the existing installation and check which APK you have; uninstalling clears the app's private data.

The version name and code are defined in the [app build configuration](../app/build.gradle.kts). The [About screen](../app/src/main/java/com/androidharness/app/ui/settings/SettingsScreen.kt) reads the installed package's version name from Android. This edition does not check upstream releases or download app updates. Plugin and toolchain updates remain available in their own settings.

See [Build, CI, and Testing](74-build-ci-and-testing.md) to build an APK from this checkout.
