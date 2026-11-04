# DroidIME

Native Android keyboard (Input Method Editor) for Thai and English.

Japanese is intentionally deferred to a separate future implementation.

## Initial scope

- Thai Kedmanee layout
- English QWERTY layout
- Switch language from the keyboard
- Shift, symbols, delete, enter, and space
- Native Android IME integration
- No network permission and no text collection

## Build

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install and enable

1. Install the APK.
2. Open DroidIME and tap **Open keyboard settings**.
3. Enable DroidIME in Android's keyboard settings.
4. Select DroidIME as the current input method.

Initial development scaffold. Japanese support is out of scope for this milestone.
