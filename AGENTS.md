# Agent entrypoint — mandatory

Before doing any work in this repository, including source inspection, planning an implementation, or editing files, bootstrap and read the central coordination repository. This file is the entrypoint; a link to the central repository is not a substitute for reading its current rules.

## 1. Bootstrap the central repository in this agent's own environment

Run this in the current agent environment. Do not ask another agent to install it, and do not install anything into another harness or machine.

```bash
AGENTS_DIR="$HOME/.agents/Agents"

gh auth status || exit 1

if [ -d "$AGENTS_DIR/.git" ]; then
  if [ -n "$(git -C "$AGENTS_DIR" status --porcelain)" ]; then
    echo "Agents checkout is dirty; preserve and resolve it before continuing."
    exit 1
  fi
  git -C "$AGENTS_DIR" fetch origin main &&
  git -C "$AGENTS_DIR" switch main &&
  git -C "$AGENTS_DIR" pull --ff-only origin main || exit 1
else
  mkdir -p "$HOME/.agents" &&
  gh repo clone Tulipskun/Agents "$AGENTS_DIR" || exit 1
fi

test "$(git -C "$AGENTS_DIR" branch --show-current)" = "main" || exit 1
test -z "$(git -C "$AGENTS_DIR" status --porcelain)" || exit 1
```

If GitHub authentication fails, request sign-in. If the clone is dirty, diverged, unavailable, or cannot be fast-forwarded, stop and report the exact blocker. Never reset, force-pull, discard local work, copy credentials from another machine, or continue using a stale copy.

## 2. Read central rules and resolve this repository

Read these files from the freshly updated clone, in this order:

1. `$HOME/.agents/Agents/AGENTS.md`
2. `$HOME/.agents/Agents/projects/registry.md`

Identify this source repository from `git remote get-url origin` and use the exact mapping in `projects/registry.md`. Do not guess the central project directory. If this repository is not listed, stop and ask the operator.

## 3. Read the canonical task before source work

From the mapped central project directory, read:

1. `state.json`
2. `handoff.md`
3. `code-map.md`
4. `task.md`

Then inspect the source repository's branch, HEAD, status, recent commits, and only the relevant diff. Verify the source commit recorded centrally. If any required central project record is missing, create it in Tulipskun/Agents, push it, and verify remote readback before touching source files.

## 4. Mandatory write gate

- Use one canonical task ID and one central task record for the whole task, even when it spans multiple repositories.
- List every in-scope repository, branch, path, owner, dependency, and acceptance criterion in the central task.
- Acquire the central project lease and task-wide lease before source edits.
- Write and push the task/state/code-map/handoff checkpoint, then fetch it again and verify the remote contents and commit.
- Only after successful remote readback may source-repository edits begin.
- Before every source push and at completion, update and verify the central state with exact source commit SHAs, changed paths, and test evidence.
- Never create repo-local task.md, state.json, handoff.md, code-map.md, or coordination checkpoints.

If any central read, write, lease, push, or readback step fails, do not edit source repositories. Report the blocker and wait for access or correction.

## 5. Harness boundary

Agents must bootstrap and follow Tulipskun/Agents themselves. Do not install or deploy hooks, daemons, CI agents, plugins, or any other enforcement software into harnesses or machines.


## Project-specific workflow

- Always push. Never leave committed work only in the local repository.
- Do not create feature/sub-branches. Commit and push directly to main.
- Version is automatic: CI sets VERSION_CODE/VERSION_NAME from the workflow run number (1.0.<run>); never hand-edit them. Pushing to main triggers the Android Release workflow, which builds the signed APK and replaces the rolling latest release.
- Finding bugs: read the whole code path before running anything; follow one input/action from entry to effect and look for asymmetry between branches. Check paired handlers end to end: touch DOWN/MOVE/UP/CANCEL, listener register/unregister, repeat start/stop.
- Verification: prove behaviour with evidence, not assumption; measure real output before claiming a match. Cover every variant, not a representative one.
- Gboard matching: measure reference screenshots, do not eyeball. Reference device is 1080px wide at density 2.8125 (384dp); measured constants are in layoutRows/rowSpec/drawKeyGlyph.
- The cloud sandbox has no Android SDK. Kotlin changes must be compile-checked using the Android build environment or GitHub Android Release workflow; do not claim a cloud-only source edit is compiled.
- Local render/build command when the configured toolchain is available: `/opt/gradle/gradle-8.9/bin/gradle --no-daemon testDebugUnitTest`.
- KeyboardRenderTest renders each keyboard state to app/build/render/<state>.png and asserts the frame is not blank. Add a state for any new page or panel.
- A layout that produces zero cells renders as one flat colour; if a new render is a single colour, check that cells are assigned.
- Update this section only for durable project-specific workflow rules, not task history.
