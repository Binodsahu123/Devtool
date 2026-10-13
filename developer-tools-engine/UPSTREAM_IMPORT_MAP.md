# Unix Shells DevTools import map

Source archive: `developer-tools-main.zip` supplied in this task.

## Imported intact

- `app/src/main/assets/devtools_frontend/**` → `src/main/assets/devtools_frontend/**`
  - All 4,058 files are retained byte-for-byte from the supplied archive.
  - No bundled JS, CSS, HTML, localization, images, fonts, or protocol metadata are replaced with custom equivalents.
- Root `LICENSE` → `LICENSE_UNIXSHELLS`
- Root `NOTICE` → `NOTICE`

## Backend retained and adapted for Swift

- Upstream `app/src/main/java/com/unixshells/devbrowser/CDPBridge.kt` → `CDPBridge.kt`
  - Same purpose: TCP-to-abstract-LocalSocket relay for WebView CDP.
  - Adaptations: loopback-only dynamic ports, request token validation, header/query sanitation, lifecycle closure and safer bidirectional proxying.
- Upstream `app/src/main/java/com/unixshells/devbrowser/DevToolsServer.kt` → `DevToolsServer.kt`
  - Same purpose: serve the bundled frontend from Android assets.
  - Adaptations: loopback-only listener, path validation, MIME types and **no hosted/Appspot fallback**.

## Swift integration glue (not a replacement frontend)

- `DeveloperToolsEngine.kt`: owns the local bridge/server and maps Swift's actual active WebView to a CDP page target.
- `CdpWebSocketConnection.kt`: real CDP WebSocket command/event transport, also used by the existing extension debugger adapter.
- `DeveloperToolsPanel.kt`: a thin Compose shell to display the imported upstream frontend; it contains no custom Elements/Console/Network implementation.

## Intentionally excluded: Unix Shells' separate browser

- `MainActivity.kt`
- `TabManager.kt`
- `SettingsActivity.kt`
- Unix Shells application module, manifest activities/providers, browser settings, tab/navigation model and app-specific UI/resources.

These exclusions are deliberate. The frontend is hosted in a Swift-owned DevTools WebView and connects to the actual Swift active site WebView through CDP. Existing Swift `BrowserScreen`, `TabEngine`, navigation, history, bookmarks and downloads remain the owners of browsing state.
