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
