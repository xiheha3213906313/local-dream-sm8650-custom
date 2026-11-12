# Release workflow

- Run code checks and build the signed APK; after success, publish directly to this repository.
- Do not run tests, screenshot reviews or additional artifact verification unless the user explicitly requests them again.
- Do not download published APKs for verification.
- Preserve the existing signing identity and package ID when building updates.
- Upload directly to GitHub Releases. Do not create temporary delivery branches or upload workflows for publication.
- Keep public release notes focused on features, fixes and installation. Do not include conversation history, user instructions, internal workflow agreements or explanations attributing verification choices to the user.
