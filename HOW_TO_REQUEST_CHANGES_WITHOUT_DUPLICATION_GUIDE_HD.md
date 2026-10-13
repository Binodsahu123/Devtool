# Swift Browser — Change Guide Without Duplicate Engines

## Source of truth

- Browser navigation, tabs and live website WebViews belong to Swift's existing `browser-engine` and `tab-engine`.
- Developer Tools belongs to `:developer-tools-engine` and receives the active tab ID/WebView from `BrowserScreen`.
- The complete imported Chromium DevTools frontend lives at `developer-tools-engine/src/main/assets/devtools_frontend/`.
- Extension `chrome.debugger` uses the production CDP transport through `extension-engine` and `developer-tools-engine`.

## Rules for future changes

1. Search for an existing implementation before adding a file, class, engine or UI panel.
2. Do not import Unix Shells' `MainActivity`, `TabManager`, `SettingsActivity`, browser navigation or tab state into Swift Browser.
3. Do not create a second/custom Elements, Console, Network or Sources implementation when working on the bundled DevTools frontend. Keep its 4,058 upstream assets intact unless intentionally upgrading them from a local Chromium build.
4. Do not make `developer-tools-engine` depend on `browser-engine`, `tab-engine` or `extension-engine`; the host passes the WebView in to prevent Gradle cycles.
5. Use the real authenticated CDP WebSocket in production. Scripted/fake CDP responses belong in tests only.
6. Keep target selection strict. Never choose an arbitrary page when URL/title metadata cannot safely identify the active Swift tab.
7. Keep the frontend server loopback-only; do not add a hosted Google/Appspot fallback or download frontend files at runtime.
8. Keep upstream license/notice files and test every relevant module after changing dependencies or interfaces.

## Verification before submitting changes

- Sync/build `:app` in Android Studio with the project's Android SDK and compatible Gradle distribution.
- Confirm the DevTools menu opens the bundled frontend for a normal website tab.
- Test Elements, Console, Network and Sources against the installed Android System WebView.
- Test switching tabs and navigating while DevTools is open; confirm a failed/ambiguous match reports an error rather than attaching to a different tab.
- Test `chrome.debugger` page-target attach, command, event and detach flows.
- Re-check upstream asset hashes if frontend files were intended to stay unchanged.

See `DEVELOPER_TOOLS_INTEGRATION_AUDIT.md` for current architecture and environment verification limits.
