# Localization

> Resolves the app locale, applies per-app language selection, ships 21 translations, and keeps technical content readable in right-to-left layouts.

The app ships English as the default resource set plus 21 translated resource directories. `AppLanguage.kt` normalizes legacy and regional locale tags, `LanguageSection` drives `AppCompatDelegate` per-app locales, and `res/xml/locales_config.xml` exposes the same list to Android 13 and newer. Around the translated UI, code, diffs, terminals, and agent output stay left-to-right, and a small set of technical keys and content stays English by design.

## Module Responsibilities

- **Base resources**: `res/values/` holds the English strings and plurals. Put related labels in the feature’s existing XML file, such as `assistant_strings.xml` or `learn_strings.xml`.
- **Translations**: each language uses a `values-*` directory. Android matches translations by resource name, not by file name.
- **Picker**: `LanguageSection` renders 23 radio rows: System default plus 22 language tags. It stores the selection through `AppCompatDelegate.setApplicationLocales`.
- **Tag normalization**: `canonicalAppLanguageTags` rewrites Tagalog to Filipino. `selectedAppLanguageTag` maps a current `Locale` back to the nearest picker tag.
- **Locale declaration**: `res/xml/locales_config.xml` lists English first and then the 21 translations. `AndroidManifest.xml` attaches it with `android:localeConfig` and sets `android:supportsRtl="true"`.
- **Persistence**: `AppLocalesMetadataHolderService` with `autoStoreLocales = true` persists the override on Android 12 and earlier. On Android 13 and newer the same preference is shared with the system per-app language UI.
- **RTL support**: the app inherits RTL layout from the locale. A few components invert direction manually, and technical surfaces opt back into LTR with `LeftToRightContent`.
- **Plurals**: `plurals` resources use CLDR quantity names. Translators supply the categories their language needs.
- **Environment helper**: `LocaleCommand` writes a minimal `locale` shell stub for the Linux environment. It is unrelated to interface translation.

## Architecture and Flow

```mermaid
flowchart TD
    A[LanguageSection row tap] --> B[AppCompatDelegate.setApplicationLocales]
    B --> C{Android 13 or newer}
    C -- yes --> D[System per-app locale storage]
    C -- no --> E[AppCompat autoStoreLocales]
    D --> F[Activity resources reload]
    E --> F
    F --> G[MainActivity.onCreate]
    G --> H[canonicalAppLanguageTags]
    H --> I{tags changed}
    I -- yes --> B
    I -- no --> J[super.onCreate and setContent]
    J --> K[Android resource resolution]
    K --> L[values-* match or English fallback]
```

- **Immediate application**: the picker does not write a bespoke preference. It calls `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`, and AppCompat applies the locale to the running app. An empty tag means System default.
- **Canonicalization before startup**: `MainActivity.onCreate` reads `getApplicationLocales().toLanguageTags()` and runs `canonicalAppLanguageTags` before `super.onCreate`. If the canonical form differs, it calls `setApplicationLocales` first. This prevents Android from preferring English over legacy Tagalog when both are requested for Filipino.
- **Resource resolution**: Android picks the best `values-*` directory for the current locale. Untranslated or missing keys fall back to `res/values`.
- **System picker integration**: `localeConfig` makes the 22 languages appear in Android Settings, Apps, AndroidHarness, Language. Because AppCompat shares the same storage, a change in either place is reflected in the other.
- **Back to picker state**: `LanguageSection` reads `AppCompatDelegate.getApplicationLocales()[0]` and passes it through `selectedAppLanguageTag`. A null locale becomes an empty string, which selects the System default row.

## Invocation Chain

1. The user opens Settings, Personalize, App language, which renders `SettingsPage.LANGUAGE` and calls `LanguageSection()`.
2. `LanguageSection` reads the current `LocaleListCompat`, resolves the selected tag, and renders the 23-row list.
3. Tapping a different row calls `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`.
4. AppCompat persists the choice through the metadata holder service on older releases and through the platform on Android 13 and newer.
5. On the next activity creation, `MainActivity.onCreate` canonicalizes the stored tag list and, if needed, writes the canonical tag back before `super.onCreate`.
6. Resource lookups then resolve through the matching `values-*` directory, with `res/values` as the final fallback.
7. Settings search builds its index in the active language and, when that is not English, builds a second index from an English `Configuration` context so English queries still match.

## Key State and Data Structures

- **Picker tags**: the ordered list in `LanguageSection` is `""` (System default), `en`, `zh-CN`, `zh-Hant`, `ru`, `ar`, `fa`, `pt-BR`, `de`, `es`, `ja`, `id`, `si`, `vi`, `tr`, `ms`, `th`, `fr`, `hi`, `ko`, `ta`, `bn`, `fil`.
- **Resource qualifier mapping**: `zh-CN` uses `values-zh-rCN`, `zh-Hant` uses `values-b+zh+Hant`, `pt-BR` uses `values-pt-rBR`, `fil` uses `values-b+fil` because it is not a two-letter language code, and Indonesian uses the legacy `values-in` qualifier while the picker and locale config use the modern `id` tag.
- **`TRANSLATION_PROJECT_URL`**: `https://poeditor.com/join/project/ZPbHNa9WWc`, opened from the contribution panel with a toast fallback on failure.
- **`locales_config.xml`**: 22 locale entries, English first: `en`, `zh-CN`, `zh-Hant`, `ru`, `ar`, `fa`, `pt-BR`, `de`, `es`, `ja`, `id`, `si`, `vi`, `tr`, `ms`, `th`, `fr`, `hi`, `ko`, `ta`, `bn`, `fil`.
- **`canonicalAppLanguageTags`**: splits a comma-separated tag list and rewrites any locale whose language is `tl` to `fil`, preserving the rest of the locale through `Locale.Builder`.
- **`selectedAppLanguageTag`**: maps `zh` by script first (`Hant` to `zh-Hant`, `Hans` to `zh-CN`), then by country (`TW`, `HK`, `MO` to `zh-Hant`, otherwise `zh-CN`). It maps all `pt` to `pt-BR`, `id` and `in` to `id`, `fil` and `tl` to `fil`, and the remaining supported languages to themselves. Anything else maps to `en`.
- **Default strings**: translations share the base resource name and format arguments. Identifiers and fixed formats marked `translatable="false"` come from the base set.
- **Language labels**: labels such as `settings_language_russian` through `settings_language_filipino` are endonyms (`Русский`, `日本語`, `Filipino`) repeated in each locale's strings file, so the picker always shows each language in its own script. The English and Simplified Chinese labels also use their native names.
- **Plural resources**: Examples include `storage_items`, `ui_model_count`, `settings_accounts_models_available`, `harness_free_retry_seconds`, and `harness_free_retry_minutes`. Consumers use `pluralStringResource` or `Resources.getQuantityString` with the actual count as the quantity.
- **`LocaleCommand`**: writes `bin/locale` when no real locale binary is installed, and refreshes an older AndroidHarness stub. The script supports no arguments, `-a`, `-m`, `charmap`, `-k charmap`, and `--help`, reports `C`, `C.UTF-8`, `en_US.UTF-8`, and `POSIX`, and rejects other charmap values. It does not generate locale data or translate UI.

## Boundary Conditions

- **Unknown locale**: `selectedAppLanguageTag` returns `en` for any language outside the supported list, so a device set to an unsupported language shows English selected rather than nothing.
- **Legacy codes**: Tagalog (`tl`) is rewritten to Filipino (`fil`) on startup. Indonesian accepts both `id` and the legacy `in`. Chinese regional locales collapse to Simplified unless the script is Traditional or the country is Taiwan, Hong Kong, or Macao.
- **Script precedence**: a Chinese locale with an explicit `Hant` script wins over its country, so `zh-Hans-TW` resolves to `zh-CN` because the script check runs first.
- **Regional variants**: `fr-CA`, `es-MX`, `pt-PT`, and similar locales map to the base supported language or to `pt-BR`. There is no `fr-CA` resource set.
- **Missing translations**: when a locale has no translation, Android uses the base resource. The app does not switch the whole interface to English.
- **Right-to-left**: Arabic and Persian are the two RTL translations. Compose mirrors padding, rows, and navigation from `LocalLayoutDirection`. `HarnessGlassSwitch` inverts drag delta when RTL so a rightward drag still means on. `AppNav.sectionMotion` multiplies the Chat/Code transition direction by minus one in RTL. `PullRequestUi` swaps its arrow glyph.
- **Technical text stays LTR**: `LeftToRightContent` forces `LayoutDirection.Ltr` and an LTR text direction and left alignment. It wraps the terminal, the code editor, Markdown code blocks, live code cards, and the visual diff viewer.
- **Plural categories**: the base file supplies `one` and `other`. Translations supply what their language requires. Russian uses `one`, `few`, `many`, and `other`. Arabic uses `zero`, `one`, `two`, `few`, `many`, and `other`. French uses `one`, `many`, and `other`. Japanese uses only `other`. A category missing for the current quantity falls back to `other`.
- **Formatting locale**: numeric and date helpers are mixed. User-facing sizes and dates often use `Locale.getDefault()` (`StorageSection`, `UiFormat`, live mode timing), while technical values such as model sizes, timestamps, and download identifiers use `Locale.US` (`LocalModelsSection`, `HuggingFaceSearch`, `SettingsScreen` export filename). This keeps decimal points and file names stable in technical output.
- **Settings search**: `settingsSearchIndex` indexes the active language and adds an English index when the active language is not English. A query typed in English still finds a translated page, and a query typed in the active language uses the translated labels.
- **Search keywords**: `SettingsPage.keywords` includes English terms and native-script variants for languages such as Russian, Arabic, Persian, Japanese, and Chinese. These keywords are search-only and are not displayed.

## Extension Points

- **Add a language**: add a `values-*` directory for the translated resources, add the locale to `locales_config.xml`, add a row in `LanguageSection`, add a `settings_language_*` label, and extend `selectedAppLanguageTag` so the saved locale maps back to the new tag.
- **Change tag normalization**: edit `canonicalAppLanguageTags` or the `when` in `selectedAppLanguageTag`. The function is the single mapping point between platform locales and picker tags.
- **Add a plural**: declare it in the base resource file with at least `one` and `other`, then add the categories each translation needs. Use `pluralStringResource` or `getQuantityString` with a real count.
- **Keep technical text LTR**: wrap the composable in `LeftToRightContent`. This is the shared helper for code, diffs, terminals, and inline technical cards.
- **Offer another translation channel**: the POEditor URL is a single constant in `LanguageSection`. Replace it to point at a different project.
- **Translate bundled content**: the Learn guide is an asset, not a resource. Each supported language ships as `assets/learn/guide-<locale>.json`; `loadLearnGuide` in `LearnGuideAssets.kt` maps the active app locale to that file and falls back to `guide.json` when it is missing or malformed.

## What Stays English

- **Model and provider names**: catalog model ids, provider names, and model display names come from provider data and are not translated.
- **Tool descriptions and agent prompts**: tool definitions and the system prompts, including the main `You are AndroidHarness...` prompt, are written in English. `FEATURES.md` documents this explicitly.
- **Shell output and logs**: terminal output, command results, and logcat lines are shown verbatim.
- **Untranslated lesson fallback**: `assets/learn/guide.json` remains the English source. Lessons fall back to English if the locale has no bundled guide or its asset cannot be loaded.
- **Technical units and identifiers**: `app_name`, `rumble_percent`, `rumble_spacing_ms`, language endonyms for English and Simplified Chinese, package names, file names, URLs, and code snippets.

## Known gaps

- When adding or changing a Learn lesson, edit the English guide and its translations together. Keep IDs, action targets and related lessons aligned; localize the text readers see.
- Guide locale matching uses language plus script/country for Chinese and Portuguese; other regional variants of a supported language share one guide.
- `LocaleCommand` can look like a localization feature but is only a compatibility stub for the bundled Linux toolchain. It reports a fixed locale set and never installs locale data.
- [LocalizationResourcesTest](../app/src/test/java/com/androidharness/app/ui/settings/LocalizationResourcesTest.kt) checks resource coverage and format arguments. [LearnCatalogTest](../app/src/test/java/com/androidharness/app/ui/learn/LearnCatalogTest.kt) checks the guide assets separately.
- The picker has no search or grouping. It is a flat list of 23 rows, with a link to the translation project.

Sources: [app/src/main/java/com/androidharness/app/ui/settings/LanguageSection.kt](../app/src/main/java/com/androidharness/app/ui/settings/LanguageSection.kt)

## Source files

- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/learn_strings.xml`
- `app/src/main/res/values/tips_strings.xml`
- `app/src/main/res/values/chat_rumble_strings.xml`
- `app/src/main/res/values/ui_strings.xml`
- `app/src/main/res/values/ui_extra_strings.xml`
- `app/src/main/res/xml/locales_config.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/androidharness/app/ui/settings/AppLanguage.kt`
- `app/src/main/java/com/androidharness/app/ui/settings/LanguageSection.kt`
- `app/src/main/java/com/androidharness/app/ui/settings/SettingsNavigation.kt`
- `app/src/main/java/com/androidharness/app/MainActivity.kt`
- `app/src/main/java/com/androidharness/app/ui/common/LeftToRightContent.kt`
- `app/src/main/java/com/androidharness/app/ui/theme/HarnessGlassSwitch.kt`
- `app/src/main/java/com/androidharness/app/ui/AppNav.kt`
- `app/src/main/java/com/androidharness/app/data/env/LocaleCommand.kt`
- `app/src/main/assets/learn/guide.json`
- `app/src/main/assets/learn/guide-*.json`
- `app/src/main/java/com/androidharness/app/ui/learn/LearnGuideAssets.kt`
