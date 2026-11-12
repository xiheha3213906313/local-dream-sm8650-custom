# Release workflow

The repository owner requested this workflow on 2026-11-12:

- Run code checks and build the signed APK; after success, publish directly to this repository.
- Do not run tests, screenshot reviews or additional artifact verification unless the user explicitly requests them again.
- Do not download published APKs for verification.
- Preserve the existing signing identity and package ID when building updates.
- Upload directly to GitHub Releases. Do not create temporary delivery branches or upload workflows for publication.
