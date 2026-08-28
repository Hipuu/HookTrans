# HookTrans

An Xposed module that translates text in other apps **in place**. Translations are display-only — hooked apps always read back the original strings, so app logic is never affected.

Requires [LSPosed](https://github.com/LSPosed/LSPosed) (Xposed API ≥ 93).

## Features

- **Five translation engines** — Google (free, no key), ML Kit (offline, on-device), DeepL, LibreTranslate, MyMemory
- **Broad hook coverage** — TextViews, input hints, WebView content, Jetpack Compose, Canvas-drawn text, image OCR, resource strings, and JSON prefetch
- **37 languages** with automatic source detection
- **Per-app targeting** via LSPosed scope selection
- **Safety controls** — skip clickable/linked text, character length filters, view ID exclusions, string blacklist, max-compatibility mode
- **Screen translate** accessibility service fallback for apps that can't be hooked directly
- **De-Googled ROM compatible** — ML Kit models are bundled in the APK, not delegated to Play Services

## Requirements

- Android 8.0+ (API 26)
- Rooted device with LSPosed installed
- For online engines: internet access
- For ML Kit: ~50 MB disk space per ABI for bundled models

## Supported Languages

English, Arabic, Bengali, Bulgarian, Chinese (Simplified & Traditional), Croatian, Czech, Danish, Dutch, Finnish, French, German, Greek, Hebrew, Hindi, Hungarian, Indonesian, Italian, Japanese, Korean, Malay, Norwegian, Persian, Polish, Portuguese, Romanian, Russian, Serbian, Slovak, Spanish, Swedish, Thai, Turkish, Ukrainian, Urdu, Vietnamese

## Translation Engines

| Engine | Key Required | Offline | Notes |
|--------|:-----------:|:-------:|-------|
| Google (free) | No | No | Default. Uses the undocumented web widget endpoint — may break without notice. |
| ML Kit | No | Yes | On-device neural translation. Bundled models, no Play Services dependency. |
| DeepL | Yes | No | Requires a DeepL API key (free or pro tier). |
| LibreTranslate | No* | Depends | Self-hosted or public instance URL required. |
| MyMemory | No | No | Free, no configuration needed. |

Engines fall back automatically: if the selected engine fails, HookTrans tries Google (free) as a last resort.

## Hook Targets

Each hook type can be toggled independently in the module settings:

| Hook | Default | Description |
|------|:-------:|-------------|
| TextViews | On | Standard views, buttons, labels |
| Hints | On | Input placeholders and helper text |
| WebView | On | DOM text inside WebView |
| Compose | On | Jetpack Compose text nodes |
| Canvas | Off | Text drawn via `Canvas.drawText` (tab bars, badges, charts). Runs every frame. |
| Resources | Off | `Resources.getString` interception. Catches menus and notifications but may touch non-display strings (URLs, keys). |
| Images | Off | OCR on bitmaps with translation overlay. Most expensive hook. |
| Prefetch | Off | Hooks JSON parsers to warm the cache with below-fold content. Increases memory and translation volume. |

## Building

```bash
./gradlew assembleRelease
```

Signing credentials are read from `local.properties`:

```properties
HOOKTRANS_STORE_PASSWORD=your_password
HOOKTRANS_KEY_ALIAS=your_alias
HOOKTRANS_KEY_PASSWORD=your_password
```

ABI-split APKs are produced for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, plus a universal APK for sideloading.

## Architecture

```
Hooked App Process          Module :engine Process
┌─────────────────┐         ┌──────────────────────┐
│  Xposed Hooks   │  Binder │   TranslatorService   │
│  (TextViewHooks,│◄───────►│   ├─ TranslationRepo  │
│   ComposeHooks, │  AIDL   │   ├─ OcrRepo          │
│   WebViewHooks, │         │   ├─ EngineClient     │
│   CanvasHooks,  │         │   └─ CacheDb          │
│   ImageHooks…)  │         │                       │
│                 │         │   TranslationEngine   │
│  HostBridge     │         │   ├─ GoogleFreeEngine │
│  (IPC client)   │         │   ├─ MlKitEngine      │
└─────────────────┘         │   ├─ DeepLEngine      │
                            │   ├─ LibreTranslate…  │
                            │   └─ MyMemoryEngine   │
                            └──────────────────────┘
```

Hooks run inside the target app's process and communicate with the translation engine over Binder IPC. The engine runs in a separate `:engine` process so ML Kit's native libraries and network calls never load into hooked apps.

## License

This project is provided as-is. Use at your own risk.
