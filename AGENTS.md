# Repository Guidelines

元记 · AI Token Vault is a Kotlin Multiplatform (Compose Multiplatform + Room + Koin + Ktor) key vault for Android and iOS. Data stays on device; never commit real credentials.

## Project Structure & Module Organization

- `app/` — Android shell (`MainActivity.kt`, `TokenVaultApp.kt`, `res/`); JVM tests in `src/test/`, instrumented tests in `src/androidTest/`.
- `shared/` — Kotlin Multiplatform module holding most code. Platform-free packages (`domain`, `endpoint`, `probe`, `balance`, `catalog`, `importer`, `backup`, `crypto`) plus `data`, `net`, `engine`, `platform`, `screens`, `ui` live in `src/commonMain/kotlin/com/lc33/tokenvault/`. Platform sources: `androidMain/`, `iosMain/`, `jvmMain/`, `jvmAndroid/` (shared by Android and JVM). Room schemas: `shared/schemas/`.
- `shared/src/commonMain/composeResources/values{,-zh-rCN}/strings.xml` — UI strings (English default, Chinese translation, matching keys).
- `iosApp/` — Xcode project and SwiftUI entry point. `docs/` — GitHub Pages site published from `master`.

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

After cloning run `git config core.hooksPath .githooks`. iOS builds need macOS and Xcode (`iosApp/iosApp.xcodeproj`).

## Coding Style & Naming Conventions

- Kotlin official style: four-space indentation, trailing commas; keep `:app:lint` clean (no formatter plugin).
- `PascalCase` classes and files, `camelCase` functions and properties, `SCREAMING_SNAKE_CASE` constants.
- Comments in Chinese, explaining *why* rather than *what*.
- No Chinese literals in Kotlin; add text to both `composeResources/values/` files with matching keys.
- Only `ui/miuix/` may import `top.yukonga.miuix`; `Window*` layers are banned—use `Overlay*`. Take colors and typography from `LocalStatusPalette` / `LocalAppTokens`.

## Testing Guidelines

- JUnit 4, `kotlin-test`, Turbine for flows, AndroidX/Room helpers for instrumented tests.
- Files are `<Subject>Test.kt` (e.g. `DefaultKeyPolicyTest.kt`); test methods use backticked Chinese names: ``fun `第一张自动成为默认`()``.
- Pure packages must avoid `android`/`androidx`/`okhttp3` imports and `Clock.System`; `ArchitectureRulesTest` and CI enforce this. New logic there needs JVM tests; `sk-TEST…` literals are allowed only in test sources.

## Commit & Pull Request Guidelines

- Chinese subjects with a conventional prefix, e.g. `fix: 修复cURL导入的iOS编译`; the `commit-msg` hook rejects other languages.
- Every commit must build independently; `pre-commit` runs `:app:compileDebugKotlin :app:testDebugUnitTest` (bypass with `SKIP_VERIFY=1`).
- Never commit API keys or `示例数据.md`; release signing uses `VAULT_RELEASE_*` environment variables.
- PRs: describe the change, link issues, list commands run, and attach before/after screenshots for UI changes.