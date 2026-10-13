# Swift Browser (Android)

Swift Browser is an Android browser project organized into Gradle modules such as `browser-engine`, `tab-engine`, `extension-engine`, and feature engines for history, bookmarks, downloads, privacy, media, and settings.

## Open the project

1. Extract the project ZIP and open its root directory in Android Studio.
2. Install the Android SDK platform/build tools expected by `compileSdk = 36` and let Android Studio sync the Gradle project.
3. Run the `app` configuration on an emulator or Android device.

This archive does not include a Gradle wrapper. Build verification therefore requires a compatible Gradle distribution and Android SDK in the development environment. Project secrets should be supplied through the documented local environment file; do not commit API keys or signing credentials.

## Developer Tools architecture

The upstream Unix Shells Developer Tools integration lives in `:developer-tools-engine`.

- `browser-engine` hosts the Developer Tools panel inside the existing `BrowserScreen`.
- `tab-engine` remains the source of truth for active tabs and their actual website `WebView`s.
- `developer-tools-engine` serves the complete bundled Chromium DevTools frontend and provides the local CDP bridge/server. It does not own browser navigation, tabs, history, bookmarks, downloads, or a separate browser Activity.
- `extension-engine` delegates page-debugger transport to the same canonical CDP connection code.

The upstream `devtools_frontend` assets are bundled locally, byte-for-byte from the supplied Unix Shells archive. No hosted DevTools frontend or runtime frontend download is used. The separate Unix Shells browser classes (`MainActivity`, `TabManager`, and `SettingsActivity`) are intentionally excluded.

See [DEVELOPER_TOOLS_INTEGRATION_AUDIT.md](DEVELOPER_TOOLS_INTEGRATION_AUDIT.md), [`developer-tools-engine/README.md`](developer-tools-engine/README.md), and [`developer-tools-engine/UPSTREAM_IMPORT_MAP.md`](developer-tools-engine/UPSTREAM_IMPORT_MAP.md) for the module wiring, source mapping, security controls, and verification status.

## Verification note

Static source/archive checks can verify the imported asset tree and project wiring, but they cannot certify on-device CDP behavior. After building, open a normal website in Swift Browser and select **Developer Tools** from the browser menu. Test Elements, Console, Network, and Sources on the target Android System WebView version.
