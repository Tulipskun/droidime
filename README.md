# DroidIME

Native Android keyboard with Thai (Kedmanee) and English layouts.

## Features

- Thai and English layouts, switched with the 🌐 key. Long-press 🌐 to pick another keyboard.
- Shift with caps lock (double-tap), plus automatic capitalization in English when the field asks for it.
- Top strip like Gboard's: tool icons (keyboard picker, undo, switch language, paste, settings, select all) that turn into three word suggestions while you type, in Thai and English. The word as typed comes first, then the best completions and, in English, one-edit corrections ("teh" offers "the"). Tapping a suggestion completes the word.
- Two pages of numbers and symbols.
- Field-aware layouts: number and phone fields open on digits; email, URL and password fields use English, with `@` or `/` next to the space bar.
- Enter key shows the field's action (Go, Search, Send, Next, Done).
- Delete replaces a selection, handles emoji, and repeats when held.
- Corner hints on keys; long-press types them (English: symbols, Thai: the shift-layer character).
- Settings: enabled languages, keyboard height, light/dark/system theme, vibration (with strength), key sound, auto-capitalization, double-space period, key hints, and a number row per language.
- In-app update check against the latest GitHub release, and a local crash log viewer.

## Install

1. Install the APK.
2. Open DroidIME and tap **Enable DroidIME**.
3. Enable DroidIME in the system list.
4. Tap **Select DroidIME** and choose it as the current keyboard.

## Build

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Release

Pushes to `main` build a signed APK and replace the single `latest` GitHub release. The signing key is fetched from the private `droidime-key` repository, and `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` must be set as repository secrets. Pull requests only build a debug APK.

## Credits

- Thai suggestions use word frequencies from the Thai National Corpus as packaged by PyThaiNLP (CC0).
- English suggestions use word frequencies from [wordfreq](https://github.com/rspeer/wordfreq) (data: CC BY-SA 4.0).
