# AGENTS.md

## Workflow

- Always push. Never leave committed work only in the local repository.
- Do not create feature/sub-branches. Commit and push directly to `main`.
- Pushing to `main` triggers the `Android Release` workflow, which builds the signed APK and replaces the rolling `latest` release.
