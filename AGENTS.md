# Repository Guidelines

元记 · AI Token Vault is a Kotlin Multiplatform (Compose Multiplatform + Room + Koin + Ktor) key vault for Android 13+ and iOS 15+, plus a JVM target used only to run tests. Secrets and account data stay on device; never commit real credentials. Chinese is the working language: code comments, commit messages, and test names are Chinese, but user-visible text must go through resources.

## Project Structure & Module Organization

- `app/` — Android shell (`MainActivity.kt`, `TokenVaultApp.kt`, `res/`); JVM tests in `src/test/`, instrumented tests in `src/androidTest/`.
- `shared/` — Kotlin Multiplatform module holding most code, including most UI. Packages under `src/commonMain/kotlin/com/lc33/tokenvault/`:
  - Platform-free (must never import platform APIs): `domain`, `endpoint`, `probe`, `balance`, `catalog`, `importer`, `backup`, `crypto`.
  - Platform-coupled: `data` (Room KMP entities/DAOs/repos), `net` (Ktor), `engine`, `platform`, `di` (Koin modules), `update` (self-update / release matching).
  - UI: `screens/` (Compose screens and `screens/model` UiState), `ui/shell/` (ViewModels), `ui/{common,theme,miuix}` (Miuix wrappers and design tokens).
  - Platform sources: `androidMain/`, `iosMain/`, `jvmMain/`, `jvmAndroid/` (compiled into both Android and JVM via `srcDir`). Room schemas: `shared/schemas/`.
- `shared/src/commonMain/composeResources/values{,-zh-rCN}/strings.xml` — UI strings (English default, Chinese translation, matching keys). Android `localeFilters` is `zh-rCN` + `en`.
- `iosApp/` — Xcode project and SwiftUI entry point. `docs/` — GitHub Pages site (`index.html`) published from `master`.
- `none.md` — the page design spec for every screen except Settings (tracked and current despite the misleading filename). `docs/UI_REFACTOR_PLAN.md` — active plan that demotes providers to Key collections and moves request/probe/balance config onto each Key. Read these before UI or schema changes.

## Build, Test, and Development Commands

Windows uses `.\gradlew.bat`, macOS/Linux `./gradlew`. Requires JDK 17 and Android SDK 37.

```bash
./gradlew :app:testDebugUnitTest   # main JVM unit-test suite
./gradlew :shared:jvmTest          # shared-module JVM tests
./gradlew :app:lint                # Android lint
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:installDebug        # install on connected device
./gradlew :app:assembleNightly     # R8 release build
```

After cloning run `git config core.hooksPath .githooks` to enable the pre-commit build/test gate and the Chinese-subject check (see Commit guidelines). iOS builds need macOS and Xcode (`iosApp/iosApp.xcodeproj`) — Kotlin/Native iOS targets cannot compile on Windows, so local Windows work only exercises Android/JVM tasks.

Network-touching probes skip themselves via `assumeTrue`; run them explicitly with:

```bash
./gradlew :app:testDebugUnitTest --tests "*ProtocolSpike*" -DvaultSpike=true --info
```

CI runs `:app:testDebugUnitTest :app:lint :app:assembleDebugAndroidTest :app:assembleNightly :shared:jvmTest` (plus iOS compile + simulator smoke on macOS). `:shared:jvmTest` matters because `DiGraphSmokeTest` is the only check that catches Koin graph/type-inference errors.

## Coding Style & Naming Conventions

- Kotlin official style: four-space indentation, trailing commas; keep `:app:lint` clean (no formatter plugin).
- `PascalCase` classes and files, `camelCase` functions and properties, `SCREAMING_SNAKE_CASE` constants.
- Comments in Chinese, explaining *why* rather than *what*.
- No Chinese string literals in Kotlin. Add every user-visible string to both `composeResources/values/` files with matching keys. A Chinese literal is allowed only when it is a parser pattern that must match pasted Chinese input (e.g. balance keywords), and then the line needs an `i18n-exempt` comment; never move such patterns into resources, or English-locale phones stop parsing Chinese data.
- Only `ui/miuix/` may import `top.yukonga.miuix`. MIUIX `Window(Dialog|BottomSheet|*Popup|*Menu|*Preference)` layers are banned (independent system windows that do not inherit `FLAG_SECURE`) — use `Overlay*` instead. Take colors and typography from `LocalStatusPalette` / `LocalAppTokens`.
- Screens are presentation only: no SQL, no `Request.Builder` in `screens/`; go through `ui/shell/` ViewModels and repositories. Page bodies must not use `AppTextButton` — dialog/sheet buttons come from `AppDialog` / `AppBottomSheet`.
- Compose UI never imports `material3`; use `androidx.compose.foundation`/`ui` plus the `ui/miuix` wrappers.
- DI is Koin (`di/CoreModule.kt`, `di/PlatformModule.kt`); do not reintroduce Hilt.
- Version name/code are declared only in `gradle.properties` (`vaultVersionCode`, `vaultVersionName`) and surfaced via generated `platform/BuildInfo.kt` and Android `BuildConfig`.
- Room KMP: entities/DAOs/database live in `data/`, schemas are exported to `shared/schemas/`. Any schema change needs a migration plus a migration test — never a destructive fallback.

## Testing Guidelines

- JUnit 4, `kotlin-test`, Turbine for flows, MockWebServer for HTTP; AndroidX/Room helpers for instrumented tests; `iosSimulatorArm64Test` on macOS.
- Files are `<Subject>Test.kt` (e.g. `DefaultKeyPolicyTest.kt`); test methods use backticked Chinese names: ``fun `第一张自动成为默认`()``.
- Pure packages must avoid `android`/`androidx`/`okhttp3` imports and `Clock.System` (inject a Clock instead); `sk-TEST…` literals are allowed only in test sources.
- `app/src/test/java/com/lc33/tokenvault/ArchitectureRulesTest.kt` is the machine check for the layer rules above and runs in `pre-commit`; CI greps again as a second gate. It enforces: pure-package isolation, MIUIX import boundary, no `Window*` layers, no SQL/HTTP in `screens/`, no Chinese literals, no `AppTextButton` in page bodies, and that each position-indexed dropdown's `string-array` item count exactly matches its Kotlin enum/`OPTIONS` list (`color_scheme_modes`, `predictive_back_styles`, `predictive_back_exit_directions`, `auto_lock_options`, `clipboard_clear_options`). Fix the code, not the test.

## Known Gotchas

- `src/jvmAndroid/` is reused by both Android and JVM through `kotlin.srcDir(...)`. Do **not** promote it to a custom intermediate source set — that breaks the default hierarchy template and detaches `iosMain` from the iOS targets.
- The shared `composeResources` → Android assets wiring is hand-rolled in `app/build.gradle.kts` because of an AGP 9 + CMP breakage. The generated Res package is `tokenvault.shared.generated.resources`; renaming the `shared` module silently breaks resources in the APK.
- Release signing reads four `VAULT_RELEASE_*` env vars (or `~/.android/tokenvault-release.properties`). When any is missing, release/nightly builds intentionally stop at an **unsigned** APK — never silently fall back to debug signing.
- Comments throughout cite `计划.md §N`, but that file is **not** in the repo — treat citations as historical references, and use `AGENTS.md`, `none.md`, and `docs/` for current guidance.

## Commit & Pull Request Guidelines

- Chinese subjects with a conventional prefix, e.g. `fix: 修复cURL导入的iOS编译`; the `commit-msg` hook rejects other languages. Every commit must build independently — `pre-commit` runs `:app:compileDebugKotlin :app:testDebugUnitTest` (bypass with `SKIP_VERIFY=1`).
- Never commit API keys, `sk-…` literals, or `示例数据.md` (real credentials; gitignored). `pre-commit` checks staged additions against the known values in that file.
- PRs: describe the change, link issues, list commands run, and attach before/after screenshots for UI changes.
