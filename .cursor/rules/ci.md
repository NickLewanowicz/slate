---
description: How to check CI/CD status and debug failures on Gitea Actions
globs: [".gitea/**", "backend/tests/**", "android/app/src/test/**"]
---

# CI/CD Pipeline

## Workflows
- **Android CI/CD** (`.gitea/workflows/android.yml`): Builds APK, runs unit tests
- **Backend CI/CD** (`.gitea/workflows/backend.yml`): Runs `bun test`, builds Docker image

## Checking Status
Load `.env` first, then:
```bash
curl -s "${GITEA_URL}/api/v1/repos/${GITEA_OWNER}/${GITEA_REPO}/actions/runs?limit=5" \
  -H "Authorization: token $GITEA_TOKEN"
```

## Running Tests Locally
```bash
# Backend
cd backend && bun test

# Android (requires JDK 17)
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

## Known Infrastructure Issues
- Backend CI Docker networking failures are runner issues, not code issues
- Android artifact upload DNS failures are runner issues
- Always run tests locally to confirm code correctness before assuming CI is broken

## See Also
- Full instructions: `CLAUDE.md`
- Claude Code skill: `.claude/skills/ci-check/SKILL.md`
