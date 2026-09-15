---
description: How to create a new Slate release on Gitea with APK
globs: ["*.apk", ".gitea/**", "android/**"]
---

# Release Process

## Environment Setup
- API credentials live in `.env` (gitignored). Copy from `.env.example` if missing.
- Load with `source .env` before any API calls.
- Never hardcode tokens in commands or files.

## Creating a Release

1. Ensure all changes committed and pushed to `main`
2. Build APK: `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug`
3. Check latest release: `curl -s "${GITEA_URL}/api/v1/repos/${GITEA_OWNER}/${GITEA_REPO}/releases?limit=1" -H "Authorization: token $GITEA_TOKEN"`
4. Create release via Gitea API: `POST /api/v1/repos/{owner}/{repo}/releases`
5. Upload APK via: `POST /api/v1/repos/{owner}/{repo}/releases/{id}/assets`

## Version Convention
- Format: `vMAJOR.MINOR.PATCH[-beta.N]`
- Pre-releases use `-beta.N` suffix
- Bump minor for features, patch for fixes

## APK Location
`android/app/build/outputs/apk/debug/app-debug.apk`

## See Also
- Full instructions: `CLAUDE.md`
- Claude Code skill: `.claude/skills/release/SKILL.md`
