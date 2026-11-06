# AGENTS.md

## Workflow

- Always push. Never leave committed work only in the local repository.
- Do not create feature/sub-branches. Commit and push directly to `main`.
- Version is automatic: CI sets `VERSION_CODE`/`VERSION_NAME` from the workflow run number (1.0.<run>); never hand-edit them. The in-app update check compares the release's versionCode with the installed one.
- Pushing to `main` triggers the `Android Release` workflow, which builds the signed APK and replaces the rolling `latest` release.

## Finding bugs

- Read the whole code path before running anything: follow one input/action from entry to effect and look for asymmetry between branches (e.g. an event handled in both DOWN and UP, or a start without a matching stop).
- Check paired handlers end to end: touch DOWN/MOVE/UP/CANCEL, listener register/unregister, repeat start/stop.

## Careful verification

- Prove behaviour with evidence, not assumption — measure the real output (pixel geometry from a reference vs the running build, actual typed text) before claiming a match.
- Cover every variant, not a representative one: each language, each row, each state.
- Do not report something verified unless it was actually checked; state plainly what was not.

## Matching Gboard

- Measure reference screenshots, don't eyeball: label connected components of non-background pixels to get key rects, then compare against `layoutRows` output. Reference device is 1080px wide at density 2.8125 (384dp); the measured constants are in `layoutRows`/`rowSpec`/`drawKeyGlyph`.
- The cloud sandbox has no Android SDK, so Kotlin changes cannot be compiled or rendered there; the GitHub `Android Release` run is the compile check, so look at it after pushing.
- A local build is possible here, but the default `gradle` on PATH is 8.7 and AGP needs ≥8.9: run `/opt/gradle/gradle-8.9/bin/gradle --no-daemon testDebugUnitTest`, which renders the keyboard states to `app/build/render/` with Robolectric, so a layout change can be checked by looking at the PNGs.

## Testing notes

- `KeyboardRenderTest` renders each keyboard state to `app/build/render/<state>.png` and asserts the frame is not blank. Add a state for any new page or panel.
- Private state is set in tests with `setField`, which walks the superclass chain — the service's fields are on the outer class, not the nested view.
- A layout that produces zero cells renders as one flat colour; that is how the clipboard panel bug (cells never assigned) was caught. If a new render is a single colour, check that `cells` is assigned.

## Maintaining this file

- Update AGENTS.md whenever a durable rule or lesson emerges; keep it lean and avoid redundant detail.
- Write guidance that helps future work, not a log of tasks already done.
