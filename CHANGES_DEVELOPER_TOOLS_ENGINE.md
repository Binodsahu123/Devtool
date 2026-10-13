# Swift Browser — Developer Tools Integration Change Log

This file summarizes the current Developer Tools integration. The detailed source audit and verification limits are in `DEVELOPER_TOOLS_INTEGRATION_AUDIT.md`.

## Implemented

- Added `:developer-tools-engine` under the Swift package namespace `com.swift.browser.developertoolsengine`.
- Imported the full Unix Shells `devtools_frontend/` assets unchanged: 4,058 files with matching SHA-256 hashes.
- Kept the upstream DevTools bridge/server purpose while adapting package integration, loopback binding, random-token authorization, error cleanup, path validation and local asset serving.
- Connected `BrowserScreen` to the active tab and existing live WebView provided by Swift `TabEngine`; Developer Tools does not create or own website tabs.
- Removed the broken browser-menu entry that reported Developer Tools as disabled.
- Removed always-on debugging from generic WebView configuration; debugging starts on demand with the Developer Tools/CDP runtime.
- Replaced the stale extension debugger transport stub with the canonical authenticated CDP WebSocket connection.
- Ported the upstream Inspect Element picker action into the Swift DevTools panel without importing Unix Shells' browser menu or Activity.
- Corrected Swift synthetic `target_tab_<uuid>` IDs versus native WebView CDP target IDs; target resolution fails closed if the active WebView cannot be safely matched.
- Restricted frontend HTTP resources to the exact local asset-server origin, made local target discovery bypass system proxies, and cleaned up failed WebSocket connections.
- Restricted extension debugger attachment to the supported CDP `1.3` protocol version.
- Moved deterministic fake CDP responses to test sources behind an injectable transport factory. The production factory uses the real socket transport.
- Removed stale documentation that referenced a different custom DevTools frontend and nonexistent source modules.

## Deliberately excluded

Unix Shells' standalone `MainActivity`, `TabManager`, `SettingsActivity`, browser navigation/state and application UI are not imported. Swift's existing `browser-engine`, `tab-engine`, `BrowserScreen`, tab state and website WebViews remain the only browser/tab owners.

## Verification status

The bundled frontend tree passes exact SHA-256 parity checks against the supplied upstream ZIP. Static source/topology checks and manifest/TOML parsing pass. The Android Gradle build and real-device CDP test have **not** been run in this environment because there is no Gradle distribution, `gradlew` wrapper or Android SDK here.

- Added a Swift-owned right-side docking mode alongside bottom and fullscreen layout.
