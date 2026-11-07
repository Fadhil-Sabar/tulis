# AGENTS.md

Tulis is an offline handwriting notes app for Android: Kotlin, plain Views (no Compose, AppCompat or Material), `androidx.ink` 1.0.0, single `:app` module, minSdk 26. The UI language is Indonesian.

## Build and test

Requires JDK 17 (`JAVA_HOME`) and the Android SDK (`ANDROID_HOME`).

```
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew installDebug
```

All three of the first must pass before committing. `assembleRelease` is minified (R8); signing reads `keystore.properties` or env vars and is skipped when absent.

## Layout (`app/src/main/java/io/github/fadhil_sabar/tulis`)

- `MainActivity` home: notebook grid, folders, vault status. About 2.5k lines.
- `EditorActivity` editor for both modes, toolbars, dialogs. About 3.8k lines. Read only the part you need and do not refactor unprompted.
- `TrashActivity` trash and restore.
- `canvas/` `NoteCanvasView` (gestures, input), `CanvasLayer` (stroke drawing), `PageRenderCache`.
- `ink/` thin wrapper over androidx.ink (`InkBridge`, `StrokeItem`).
- `model/` `Models`, `PageStack` (paged scroll model), `Viewport`.
- `storage/` `NotebookRepository` (files under `filesDir/notebooks/`), `PageCodec` (binary page format), `NotebookArchive` (`.tulis` zip), `VaultSync` and `VaultMarkdown` (Obsidian vault via SAF).
- `export/` `PageRenderer` and `Exporter` (PDF/PNG), independent of `CanvasLayer`.
- `ui/` custom widgets and dialogs (`SketchDrawable`, `SketchSlider`, `MinimapView`, `Icons`, `Fonts`, `UiPrefs`, ...).

Design mockups and older planning notes are in `docs/` and are historical.

## Do not change without discussion

- Permissions: the app requests none. Do not add `INTERNET` or any dependency.
- Storage formats: `PageCodec.VERSION` and `NotebookArchive.FORMAT`. A format change must bump the version, keep reading the old one, and have a unit test.
- `applicationId`, signing, `versionCode`/`versionName`: only change in a release commit.
- `backup_rules.xml` and `data_extraction_rules.xml` must stay in sync.

## Known pitfalls

- A clickable View without a listener inside a clickable parent swallows touches. Decorative `IconView`s must set `isClickable = false`.
- `canvasView.invalidate()` does not redraw `canvasLayer`; call `invalidatePages()`.
- SAF/vault access is slow and the permission can be revoked; handle `needRepick` and run it off the main thread.
- Slider number fields can hold stale text; update them from the model, not the other way round.

## Conventions

- UI strings are Indonesian and hardcoded, following the existing pattern.
- Do not add code comments unless asked.
- Kotlin official style. New storage logic needs JVM unit tests (see `app/src/test`, JUnit4 with `org.json`).
- Commit subjects are imperative sentences, Indonesian preferred, English accepted. One logical change per commit.
- Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.
- Never commit `NOTES*.md`, `CONTRACT*.md`, `keystore.properties`, `*.jks`, or `local.properties`.

## Testing limits

Stylus and tablet behavior is verified on the owner's device. In a PR, list manual steps you ran and what remains unverified.

## Release

Bump `versionName` (SemVer) and `versionCode` (`major*10000 + minor*100 + patch`) in `app/build.gradle.kts`, move `[Unreleased]` to the new version in `CHANGELOG.md`, commit `Rilis X.Y.Z`, then `git tag -a vX.Y.Z -m vX.Y.Z` and push the tag. The `Release` workflow builds the signed APK and creates the GitHub Release.
