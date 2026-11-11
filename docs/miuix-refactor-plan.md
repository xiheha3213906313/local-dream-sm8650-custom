# Miuix UI redesign

Goal: rebuild the presentation and navigation with Miuix 0.9.0 while keeping inference, model files, history and preferences intact. Prioritize readable hierarchy, independent tasks, full-page imports and accessibility at large font scales.

- [x] Inspect existing flows, pin compatible official Miuix library; reclaim only generated local delivery objects.
- [x] Introduce Miuix theme and interoperable controls preserving text selection / IME / autocomplete contracts.
- [x] Dedicated model import destinations with model-family selection, file roles and full wrapping filenames; checkpoint/archive imports also become pages.
- [x] Generation workspace: fixed non-scrolling viewport; all parameters in one grid, inline editors, paired positive/negative inputs and a collapsible parameter grid, stable primary action and result/history navigation.
- [x] Model library and settings navigation, result/history actions and remaining screens use consistent Miuix controls and spacing.
- [x] Verify small widths and 1.3/1.5/2.0 font scales, render screenshots and fix overflow rather than shrinking text.
- [x] Build, tests, same-certificate signing, APK CRC/alignment and staged native library checks.
- [x] Push source and publish GitHub release: source commit `f269b22`, release `sm8650-miuix-v0.11` in `xiheha3213906313/local-dream-sm8650-custom`. Published assets were checked through metadata only; no post-upload download.

User clarification: large-font support must keep action controls compact. Button names stay on one line; long guidance expands separately. Never grow buttons by wrapping labels into multiple lines.

Final steering: generation parameters must remain on one non-scrolling page. Miuix theme is retained, with custom compact control dimensions instead of strict component defaults.

Final input layout: positive and negative prompts are both visible; the lower parameter grid can collapse without scrolling the page or losing prompt text.

## v0.12 feedback

- [x] Auto-collapse parameters during input; a compact independent negative prompt below the positive prompt and above parameters.
- [x] Toolbar overflow, anchored library menu and evenly sized model tabs.
- [x] Image actions above the positive prompt; tap opens a parameter card, hold + drag adjusts values.
- [x] Remove stale collapsing app-bar scroll connections; test real list/result dragging and full result text.
- [x] Final feedback: icon-only image actions, independent negative prompt below positive input, no divider; 82 tests passed; final divider removal passed 8 focused layout/input checks.
- [x] Build and verify signing locally; source `2f50bd1` pushed and release `sm8650-miuix-v0.12` published. Only publication/asset metadata were checked afterward; no asset downloads.

## v0.13 feedback

- [x] Replace the ordinary parameter dialog with a surface whose bounds animate from/to the tapped tile in the same workspace window.
- [x] Explicit option borders and selected state, visible outlined numeric input, and distinct random/fixed seed modes.
- [x] Raise the live parameter value above the pointer while dragging; accumulate motion from the gesture's starting value.
- [x] Caption image actions, consolidate editing tools into one row, remove duplicate thumbnail-side action buttons.
- [x] Cap the positive prompt region so the independent negative prompt immediately follows its actual border.
- [ ] Final large-font, animation and input verification; same-certificate APK build and publication without downloading published assets.
